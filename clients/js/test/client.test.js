// Contract tests for the JS HermesMQ client against an in-process node:http
// stub — hermetic, no broker needed. The stub records the last request
// (method, path, JSON body, auth headers) so tests can assert wire shapes.
import { createServer } from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { HermesClient, HermesClientError } from "../index.js";

let server;
let baseUrl;
let client;
const last = { method: "", path: "", body: {}, authorization: "", apiKey: "" };

function json(res, status, payload) {
  const data = JSON.stringify(payload);
  res.writeHead(status, { "content-type": "application/json" });
  res.end(data);
}

beforeAll(async () => {
  server = createServer((req, res) => {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      const body = raw ? JSON.parse(raw) : {};
      last.method = req.method;
      last.path = req.url;
      last.body = body;
      last.authorization = req.headers["authorization"] ?? "";
      last.apiKey = req.headers["x-api-key"] ?? "";

      const { method, url } = req;
      if (method === "POST" && url === "/v1/topics") {
        res.writeHead(body.topicId === "dup" ? 409 : 201).end();
      } else if (method === "GET" && url === "/v1/topics") {
        json(res, 200, [{ topicId: "orders", publishedTotal: 42, deleted: false }]);
      } else if (method === "GET" && url === "/v1/topics/orders") {
        json(res, 200, { topicId: "orders", labels: { team: "payments" } });
      } else if (method === "GET" && url.startsWith("/v1/topics/")) {
        json(res, 404, { error: "no such topic" });
      } else if (method === "PATCH" && url === "/v1/topics/orders") {
        res.writeHead(200).end();
      } else if (method === "DELETE" && url === "/v1/topics/orders") {
        res.writeHead(204).end();
      } else if (method === "POST" && /^\/v1\/topics\/[^/]+\/messages$/.test(url)) {
        if (url.includes("/ghost/")) json(res, 404, { error: "no such topic" });
        else if (url.includes("/plaintype/")) {
          // 2xx, JSON body, non-JSON content-type label — delivery must be
          // judged by status, not the label (the Demeter trap).
          res.writeHead(202, { "content-type": "text/plain" });
          res.end(JSON.stringify({ messageId: "m-plain", deduplicated: false }));
        } else if (body.idempotencyKey === "idem-1")
          json(res, 202, { messageId: "m-orig", deduplicated: true });
        else json(res, 202, { messageId: "m-123", deduplicated: false });
      } else if (method === "POST" && url === "/v1/subscriptions") {
        res.writeHead(body.subscriptionId === "dupsub" ? 409 : 201).end();
      } else if (method === "GET" && url === "/v1/subscriptions") {
        json(res, 200, [
          {
            subscriptionId: "s1",
            topicId: "orders",
            backlog: 3,
            oldestUnackedAgeSeconds: 7,
            redeliveredTotal: 1,
            deadLetteredTotal: 0,
          },
        ]);
      } else if (method === "DELETE" && url === "/v1/subscriptions/s1") {
        res.writeHead(204).end();
      } else if (method === "DELETE" && url.startsWith("/v1/subscriptions/")) {
        json(res, 404, { error: "not found" });
      } else if (method === "POST" && /^\/v1\/subscriptions\/[^/]+\/pull$/.test(url)) {
        if (url.includes("/ghost/")) json(res, 404, { error: "no such subscription" });
        else
          json(res, 200, {
            messages: [
              {
                ackId: "a1",
                payload: "hello",
                attributes: { k: "v" },
                publishTime: "2026-07-08T00:00:00Z",
              },
            ],
          });
      } else if (method === "POST" && /^\/v1\/subscriptions\/[^/]+\/ack$/.test(url)) {
        json(res, 200, { acknowledged: ["a1"], unknown: ["a-stale"] });
      } else if (method === "POST" && /^\/v1\/subscriptions\/[^/]+\/modifyAckDeadline$/.test(url)) {
        json(res, 200, { modified: ["a1"], unknown: ["a-stale"] });
      } else if (method === "GET" && url === "/health") {
        json(res, 200, { status: "UP", service: "hermesmq", version: "1.11.0" });
      } else {
        res.writeHead(404).end();
      }
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
  client = new HermesClient(baseUrl);
});

afterAll(() => new Promise((resolve) => server.close(resolve)));

describe("topics", () => {
  it("creates a topic and sends the right body", async () => {
    await client.createTopic("orders", { team: "payments" });
    expect(last.body).toEqual({ topicId: "orders", labels: { team: "payments" } });
  });

  it("rejects a duplicate create with a typed 409 error", async () => {
    const err = await client.createTopic("dup").catch((e) => e);
    expect(err).toBeInstanceOf(HermesClientError);
    expect(err.status).toBe(409);
  });

  it("reads a topic's labels", async () => {
    expect(await client.getTopic("orders")).toEqual({
      topicId: "orders",
      labels: { team: "payments" },
    });
  });

  it("resolves null for a missing topic", async () => {
    expect(await client.getTopic("ghost")).toBeNull();
  });

  it("updates and deletes a topic", async () => {
    await client.updateTopic("orders", { team: "core" });
    expect(last.body).toEqual({ labels: { team: "core" } });
    await client.deleteTopic("orders");
  });

  it("lists topics with published totals", async () => {
    const topics = await client.listTopics();
    expect(topics).toEqual([{ topicId: "orders", publishedTotal: 42, deleted: false }]);
  });
});

describe("publish & consume", () => {
  it("publishes and returns id + dedup flag", async () => {
    const result = await client.publish("orders", "hello", { attributes: { k: "v" } });
    expect(result).toEqual({ messageId: "m-123", deduplicated: false });
  });

  it("forwards publish options in the body", async () => {
    await client.publish("orders", "hello", {
      ttlSeconds: 60,
      idempotencyKey: "key-9",
      producerId: "ingest-1",
    });
    expect(last.body.ttlSeconds).toBe(60);
    expect(last.body.idempotencyKey).toBe("key-9");
    expect(last.body.producerId).toBe("ingest-1");
  });

  it("surfaces a deduplicated publish", async () => {
    const result = await client.publish("orders", "hello", { idempotencyKey: "idem-1" });
    expect(result).toEqual({ messageId: "m-orig", deduplicated: true });
  });

  it("counts a 2xx publish as delivered even when the content type is not JSON", async () => {
    const result = await client.publish("plaintype", "hello");
    expect(result).toEqual({ messageId: "m-plain", deduplicated: false });
  });

  it("rejects publishing to a missing topic", async () => {
    const err = await client.publish("ghost", "x").catch((e) => e);
    expect(err).toBeInstanceOf(HermesClientError);
    expect(err.status).toBe(404);
  });

  it("creates and deletes a subscription", async () => {
    await client.createSubscription("s1", "orders");
    expect(last.body).toEqual({ subscriptionId: "s1", topicId: "orders" });
    await client.deleteSubscription("s1");
  });

  it("rejects deleting a missing subscription with the status", async () => {
    const err = await client.deleteSubscription("ghost").catch((e) => e);
    expect(err).toBeInstanceOf(HermesClientError);
    expect(err.status).toBe(404);
  });

  it("pulls messages and forwards max + consumerId", async () => {
    const messages = await client.pull("s1", { max: 5, consumerId: "worker-1" });
    expect(messages.map((m) => m.ackId)).toEqual(["a1"]);
    expect(messages[0].payload).toBe("hello");
    expect(last.body).toEqual({ max: 5, consumerId: "worker-1" });
  });

  it("returns acknowledged/unknown from ack", async () => {
    const result = await client.ack("s1", ["a1"]);
    expect(result).toEqual({ acknowledged: ["a1"], unknown: ["a-stale"] });
  });

  it("modifies ack deadlines", async () => {
    const result = await client.modifyAckDeadline("s1", ["a1"], 30);
    expect(result).toEqual({ modified: ["a1"], unknown: ["a-stale"] });
    expect(last.body).toEqual({ ackIds: ["a1"], ackDeadlineSeconds: 30 });
  });

  it("surfaces an unreachable listing as an error, never an empty result", async () => {
    // "couldn't ask" must stay distinguishable from "nobody is listening".
    const broken = new HermesClient(`${baseUrl}/nope`);
    const err = await broken.listSubscriptions().catch((e) => e);
    expect(err).toBeInstanceOf(HermesClientError);
  });

  it("lists subscriptions with stats", async () => {
    const subs = await client.listSubscriptions();
    expect(subs[0].subscriptionId).toBe("s1");
    expect(subs[0].backlog).toBe(3);
  });
});

describe("observability & auth", () => {
  it("reports broker health", async () => {
    expect(await client.health()).toEqual({ status: "UP", service: "hermesmq", version: "1.11.0" });
  });

  it("sends no auth headers by default", async () => {
    await client.health();
    expect(last.authorization).toBe("");
    expect(last.apiKey).toBe("");
  });

  it("sends a bearer token when configured", async () => {
    await new HermesClient(baseUrl, { token: "t1" }).health();
    expect(last.authorization).toBe("Bearer t1");
  });

  it("sends an api key when configured", async () => {
    await new HermesClient(baseUrl, { apiKey: "k1" }).health();
    expect(last.apiKey).toBe("k1");
  });
});
