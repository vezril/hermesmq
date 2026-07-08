package me.cference.hermesmq.domain

/** Commands accepted by the Topic aggregate's write side. */
enum TopicCommand:
  case CreateTopic(topicId: TopicId)
  case Publish(message: Message)

/** Events emitted by the Topic aggregate. */
enum TopicEvent:
  case TopicCreated(topicId: TopicId)
  case MessagePublished(message: Message)

/** In-memory Topic state. A topic either does not yet exist or exists with an
  * id. Published messages are not retained in topic state in this basic model.
  */
final case class TopicState(topicId: Option[TopicId]):
  def exists: Boolean = topicId.isDefined

/** Pure Topic aggregate: the write-side decision and state-evolution functions.
  * Both are total — `decide` returns a `Left(Rejection)` rather than throwing,
  * and `evolve` is defined for every event.
  */
object Topic:

  val empty: TopicState = TopicState(topicId = None)

  def decide(state: TopicState, command: TopicCommand): Either[Rejection, List[TopicEvent]] =
    command match
      case TopicCommand.CreateTopic(topicId) =>
        if state.exists then Left(Rejection.TopicAlreadyExists)
        else Right(List(TopicEvent.TopicCreated(topicId)))

      case TopicCommand.Publish(message) =>
        if state.exists then Right(List(TopicEvent.MessagePublished(message)))
        else Left(Rejection.TopicNotFound)

  def evolve(state: TopicState, event: TopicEvent): TopicState =
    event match
      case TopicEvent.TopicCreated(topicId) => state.copy(topicId = Some(topicId))
      case TopicEvent.MessagePublished(_)   => state
