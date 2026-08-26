/**
 * Typed, zero-dependency JavaScript client for the HermesMQ REST API.
 * Uses the global `fetch` (Node ≥ 18 or any browser) — no runtime deps, so it
 * can also be vendored into a service as a single file (pair with index.d.ts).
 *
 * Error model: non-2xx responses reject with {@link HermesClientError} carrying
 * the HTTP status and response body — except `getTopic` on 404, which resolves
 * to `null` (reading a missing thing is normal, not exceptional).
 */

/** Raised when the broker returns an unexpected/error status. */
export class HermesClientError extends Error {
  /**
   * @param {number} status
   * @param {string} body
   */
  constructor(status, body) {
    super(`HermesMQ request failed with ${status}: ${body}`);
    this.name = "HermesClientError";
    this.status = status;
    this.body = body;
  }
}

export class HermesClient {
  /**
   * @param {string} baseUrl - The broker's base URL, e.g. `http://hermesmq.hermesmq.svc.cluster.local:8080`.
   * @param {{token?: string, apiKey?: string, fetch?: typeof fetch}} [options]
   *   Optional bearer `token` (sent as `Authorization: Bearer`) and/or `apiKey`
   *   (sent as `X-API-Key`); neither is sent by default. A custom `fetch` can be
   *   injected for testing or instrumentation.
   */
  constructor(baseUrl, options = {}) {
    this.baseUrl = baseUrl.replace(/\/$/, "");
    this.headers = { "content-type": "application/json" };
    if (options.token !== undefined) this.headers["authorization"] = `Bearer ${options.token}`;
    if (options.apiKey !== undefined) this.headers["x-api-key"] = options.apiKey;
    this.fetch = options.fetch ?? globalThis.fetch;
  }

  // -- topics ------------------------------------------------------------

  /** @param {string} topicId @param {Record<string, string>} [labels] */
  async createTopic(topicId, labels = {}) {
    await this.request("POST", "/v1/topics", { body: { topicId, labels }, ok: [201] });
  }

  /**
   * @param {string} topicId
   * @returns {Promise<{topicId: string, labels: Record<string, string>} | null>}
   */
  async getTopic(topicId) {
    const res = await this.send("GET", `/v1/topics/${encodeURIComponent(topicId)}`);
    if (res.status === 404) return null;
    return this.json(res, [200]);
  }

  /** @param {string} topicId @param {Record<string, string>} labels */
  async updateTopic(topicId, labels) {
    await this.request("PATCH", `/v1/topics/${encodeURIComponent(topicId)}`, {
      body: { labels },
      ok: [200],
    });
  }

  /** @param {string} topicId */
  async deleteTopic(topicId) {
    await this.request("DELETE", `/v1/topics/${encodeURIComponent(topicId)}`, { ok: [204] });
  }

  /** @returns {Promise<Array<{topicId: string, publishedTotal: number, deleted: boolean}>>} */
  async listTopics() {
    return this.json(await this.send("GET", "/v1/topics"), [200]);
  }

  // -- publish -----------------------------------------------------------

  /**
   * @param {string} topicId
   * @param {string} payload
   * @param {{attributes?: Record<string, string>, ttlSeconds?: number, idempotencyKey?: string, producerId?: string, correlationId?: string}} [options]
   * @returns {Promise<{messageId: string, deduplicated: boolean}>}
   */
  async publish(topicId, payload, options = {}) {
    const body = { payload, attributes: options.attributes ?? {} };
    if (options.ttlSeconds !== undefined) body.ttlSeconds = options.ttlSeconds;
    if (options.idempotencyKey !== undefined) body.idempotencyKey = options.idempotencyKey;
    if (options.producerId !== undefined) body.producerId = options.producerId;
    // The REST publish adopts the X-Correlation-Id header as the message's
    // correlation id (request-tracing); delivered verbatim to consumers.
    const headers =
      options.correlationId !== undefined ? { "x-correlation-id": options.correlationId } : undefined;
    const data = await this.request(
      "POST",
      `/v1/topics/${encodeURIComponent(topicId)}/messages`,
      { body, ok: [202, 201], json: true, headers }
    );
    return { messageId: data.messageId, deduplicated: data.deduplicated ?? false };
  }

  // -- subscriptions -----------------------------------------------------

  /** @param {string} subscriptionId @param {string} topicId */
  async createSubscription(subscriptionId, topicId) {
    await this.request("POST", "/v1/subscriptions", {
      body: { subscriptionId, topicId },
      ok: [201],
    });
  }

  /**
   * Deletes the subscription and drops its backlog. The id stays reserved
   * broker-side (its journal persists) — it cannot be recreated with the same name.
   * @param {string} subscriptionId
   */
  async deleteSubscription(subscriptionId) {
    await this.request("DELETE", `/v1/subscriptions/${encodeURIComponent(subscriptionId)}`, {
      ok: [204],
    });
  }

  /**
   * @returns {Promise<Array<{subscriptionId: string, topicId: string, backlog: number,
   *   oldestUnackedAgeSeconds: number, redeliveredTotal: number, deadLetteredTotal: number}>>}
   */
  async listSubscriptions() {
    return this.json(await this.send("GET", "/v1/subscriptions"), [200]);
  }

  // -- consume -----------------------------------------------------------

  /**
   * @param {string} subscriptionId
   * @param {{max?: number, consumerId?: string}} [options]
   * @returns {Promise<Array<{ackId: string, payload: string, attributes: Record<string, string>, publishTime: string, correlationId?: string}>>}
   */
  async pull(subscriptionId, options = {}) {
    const body = { max: options.max ?? 10 };
    if (options.consumerId !== undefined) body.consumerId = options.consumerId;
    const data = await this.request(
      "POST",
      `/v1/subscriptions/${encodeURIComponent(subscriptionId)}/pull`,
      { body, ok: [200], json: true }
    );
    return data.messages;
  }

  /**
   * @param {string} subscriptionId
   * @param {string[]} ackIds
   * @returns {Promise<{acknowledged: string[], unknown: string[]}>}
   */
  async ack(subscriptionId, ackIds) {
    return this.request("POST", `/v1/subscriptions/${encodeURIComponent(subscriptionId)}/ack`, {
      body: { ackIds },
      ok: [200],
      json: true,
    });
  }

  /**
   * @param {string} subscriptionId
   * @param {string[]} ackIds
   * @param {number} ackDeadlineSeconds
   * @returns {Promise<{modified: string[], unknown: string[]}>}
   */
  async modifyAckDeadline(subscriptionId, ackIds, ackDeadlineSeconds) {
    return this.request(
      "POST",
      `/v1/subscriptions/${encodeURIComponent(subscriptionId)}/modifyAckDeadline`,
      { body: { ackIds, ackDeadlineSeconds }, ok: [200], json: true }
    );
  }

  // -- observability -----------------------------------------------------

  /** @returns {Promise<{status: string, service: string, version: string}>} */
  async health() {
    return this.json(await this.send("GET", "/health"), [200]);
  }

  // -- plumbing ----------------------------------------------------------

  /** @private @param {string} method @param {string} path @param {object} [body] @param {Record<string,string>} [extraHeaders] */
  async send(method, path, body, extraHeaders) {
    return this.fetch(`${this.baseUrl}${path}`, {
      method,
      headers: extraHeaders ? { ...this.headers, ...extraHeaders } : this.headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }

  /**
   * @private
   * @param {string} method @param {string} path
   * @param {{body?: object, ok: number[], json?: boolean, headers?: Record<string, string>}} spec
   */
  async request(method, path, spec) {
    const res = await this.send(method, path, spec.body, spec.headers);
    if (!spec.ok.includes(res.status)) {
      throw new HermesClientError(res.status, await res.text());
    }
    return spec.json ? res.json() : undefined;
  }

  /** @private @param {Response} res @param {number[]} ok */
  async json(res, ok) {
    if (!ok.includes(res.status)) throw new HermesClientError(res.status, await res.text());
    return res.json();
  }
}
