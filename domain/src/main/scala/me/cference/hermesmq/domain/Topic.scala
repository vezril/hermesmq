package me.cference.hermesmq.domain

/** Commands accepted by the Topic aggregate's write side. */
enum TopicCommand:
  case CreateTopic(topicId: TopicId, labels: Map[String, String] = Map.empty)
  case Publish(message: Message)
  case DeleteTopic
  case UpdateTopic(labels: Map[String, String])

/** Events emitted by the Topic aggregate. */
enum TopicEvent:
  case TopicCreated(topicId: TopicId, labels: Map[String, String] = Map.empty)
  case MessagePublished(message: Message)
  case TopicDeleted(topicId: TopicId)
  case TopicLabelsUpdated(topicId: TopicId, labels: Map[String, String])

/** In-memory Topic state. A topic id, once used, stays recorded; `deleted`
  * marks a soft delete. `active` (created and not deleted) is the state in which
  * publish/update/delete are accepted. Published messages are not retained here.
  */
final case class TopicState(topicId: Option[TopicId], labels: Map[String, String], deleted: Boolean):
  /** The id has been used at some point (active or deleted). */
  def created: Boolean = topicId.isDefined

  /** Created and not deleted — the operable state. */
  def active: Boolean = created && !deleted

  /** Retained for callers/tests: a topic "exists" when it is active. */
  def exists: Boolean = active

/** Pure Topic aggregate: the write-side decision and state-evolution functions.
  * Both are total — `decide` returns a `Left(Rejection)` rather than throwing,
  * and `evolve` is defined for every event.
  */
object Topic:

  val empty: TopicState = TopicState(topicId = None, labels = Map.empty, deleted = false)

  def decide(state: TopicState, command: TopicCommand): Either[Rejection, List[TopicEvent]] =
    command match
      case TopicCommand.CreateTopic(topicId, labels) =>
        // Once an id is used it cannot be re-created, even after deletion.
        if state.created then Left(Rejection.TopicAlreadyExists)
        else Right(List(TopicEvent.TopicCreated(topicId, labels)))

      case TopicCommand.Publish(message) =>
        onActive(state)(_ => TopicEvent.MessagePublished(message))

      case TopicCommand.DeleteTopic =>
        onActive(state)(id => TopicEvent.TopicDeleted(id))

      case TopicCommand.UpdateTopic(labels) =>
        onActive(state)(id => TopicEvent.TopicLabelsUpdated(id, labels))

  def evolve(state: TopicState, event: TopicEvent): TopicState =
    event match
      case TopicEvent.TopicCreated(topicId, labels) =>
        state.copy(topicId = Some(topicId), labels = labels, deleted = false)
      case TopicEvent.MessagePublished(_) => state
      case TopicEvent.TopicDeleted(_)     => state.copy(deleted = true)
      case TopicEvent.TopicLabelsUpdated(_, labels) => state.copy(labels = labels)

  /** Emit an event derived from the active topic's id, or reject as not-found. */
  private def onActive(state: TopicState)(
      event: TopicId => TopicEvent
  ): Either[Rejection, List[TopicEvent]] =
    state.topicId.filter(_ => state.active) match
      case Some(id) => Right(List(event(id)))
      case None     => Left(Rejection.TopicNotFound)
