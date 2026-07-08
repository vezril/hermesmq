package me.cference.hermesmq

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

/** Smoke test: proves the Pekko typed runtime is wired correctly by spinning up
  * a minimal actor and asserting it replies. `ScalaTestWithActorTestKit` shuts
  * the ActorTestKit (and its actor system) down in `afterAll`, so no dispatcher
  * threads leak once the suite finishes.
  */
final class PingPongSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "The Ponger actor" should {
    "reply with Pong when it receives a Ping" in {
      val probe  = createTestProbe[Ponger.Pong.type]()
      val ponger = spawn(Ponger())

      ponger ! Ponger.Ping(probe.ref)

      probe.expectMessage(Ponger.Pong)
    }
  }
}
