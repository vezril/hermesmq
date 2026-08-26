/** Typed, zero-dependency JavaScript client for the HermesMQ REST API. */

export interface TopicInfo {
  topicId: string;
  labels: Record<string, string>;
}

export interface TopicStats {
  topicId: string;
  publishedTotal: number;
  deleted: boolean;
}

export interface SubscriptionStats {
  subscriptionId: string;
  topicId: string;
  backlog: number;
  oldestUnackedAgeSeconds: number;
  redeliveredTotal: number;
  deadLetteredTotal: number;
}

export interface PublishOptions {
  attributes?: Record<string, string>;
  ttlSeconds?: number;
  idempotencyKey?: string;
  producerId?: string;
}

export interface PublishResult {
  messageId: string;
  deduplicated: boolean;
}

export interface ReceivedMessage {
  ackId: string;
  payload: string;
  attributes: Record<string, string>;
  publishTime: string;
}

export interface PullOptions {
  max?: number;
  consumerId?: string;
}

export interface AckResult {
  acknowledged: string[];
  unknown: string[];
}

export interface ModifyAckDeadlineResult {
  modified: string[];
  unknown: string[];
}

export interface HealthInfo {
  status: string;
  service: string;
  version: string;
}

export interface HermesClientOptions {
  /** Sent as `Authorization: Bearer <token>` on every request. */
  token?: string;
  /** Sent as `X-API-Key` on every request. */
  apiKey?: string;
  /** Custom fetch implementation (testing/instrumentation). */
  fetch?: typeof fetch;
}

/** Rejection carrying the broker's HTTP status and response body. */
export class HermesClientError extends Error {
  status: number;
  body: string;
  constructor(status: number, body: string);
}

export class HermesClient {
  constructor(baseUrl: string, options?: HermesClientOptions);

  createTopic(topicId: string, labels?: Record<string, string>): Promise<void>;
  /** Resolves to `null` when the topic does not exist (404). */
  getTopic(topicId: string): Promise<TopicInfo | null>;
  updateTopic(topicId: string, labels: Record<string, string>): Promise<void>;
  deleteTopic(topicId: string): Promise<void>;
  listTopics(): Promise<TopicStats[]>;

  publish(topicId: string, payload: string, options?: PublishOptions): Promise<PublishResult>;

  createSubscription(subscriptionId: string, topicId: string): Promise<void>;
  /**
   * Deletes the subscription and drops its backlog. The id stays reserved
   * broker-side — it cannot be recreated with the same name.
   */
  deleteSubscription(subscriptionId: string): Promise<void>;
  listSubscriptions(): Promise<SubscriptionStats[]>;

  pull(subscriptionId: string, options?: PullOptions): Promise<ReceivedMessage[]>;
  ack(subscriptionId: string, ackIds: string[]): Promise<AckResult>;
  modifyAckDeadline(
    subscriptionId: string,
    ackIds: string[],
    ackDeadlineSeconds: number
  ): Promise<ModifyAckDeadlineResult>;

  health(): Promise<HealthInfo>;
}
