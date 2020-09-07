package io.bernhardt.akka.behaviortrees

import io.bernhardt.akka.behaviortrees.BehaviorTree._
import org.slf4j.Logger

import scala.concurrent.Future

object App {

  def main(args: Array[String]): Unit = {

    val world = new World

    val tree = RootNode(
      SequenceNode(
        FallbackNode(
          ConditionNode((world: World) => world.canMove, world),
          ActionNode((world: World, logger: Logger) => Future.successful {
            world.turn()
            logger.info("Robot turns")
            Success
          }, world)
        ),
        ActionNode((world: World, logger: Logger) => Future.successful {
          world.move()
          logger.info("Robot moves")
          Success
        }, world)
      )
    )

    tree.run()

  }

}


/**
 * A unidimensional universe in which the robot lives
 */
class World {
  val Length = 5

  private var movesForward = true
  private var robotPosition = 0

  def canMove: Boolean = if (movesForward) robotPosition < Length else robotPosition >= 0
  def move(): Unit = if(movesForward) robotPosition += 1 else robotPosition -= 1
  def turn(): Unit = movesForward = !movesForward
}