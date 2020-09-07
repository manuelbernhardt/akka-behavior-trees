package io.bernhardt.akka.behaviortrees

import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.pattern.after
import org.slf4j.Logger

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure => TryFailure, Success => TrySuccess}

object BehaviorTree {

  implicit val TickDuration = 1.second
  implicit val TickTimeout = 800.millis
  implicit val InstantaneousActionTimeout = 100.millis

  sealed trait Command
  case class Tick(parent: ActorRef[TickResult]) extends Command
  case class TickResult(result: Result, node: ActorRef[Command]) extends Command
  case class CreateChildren(children: Seq[Node]) extends Command

  // TODO those two should be part of the internal protocol of action nodes
  case class ActionResult(result: Result, parent: ActorRef[TickResult]) extends Command
  case class ActionNotInstantaneous(parent: ActorRef[TickResult], action: Future[Result]) extends Command

  sealed trait Result
  case object Success extends Result
  case object Failure extends Result
  case object Running extends Result

  case class Results(
    successes: Seq[ActorRef[TickResult]] = Seq.empty,
    failures: Seq[ActorRef[TickResult]] = Seq.empty,
    running: Seq[ActorRef[TickResult]] = Seq.empty)

  sealed trait ControlFlowNode {
    def deferredResultTypes: Set[Result]
    def checkReturn(results: Results, childCount: Int): Option[Result]
    def updateResults(results: Results, tickResult: TickResult): Results
  }
  case object Sequence extends ControlFlowNode {
    override def deferredResultTypes: Set[Result] = Set(Success)
    override def checkReturn(results: Results, childCount: Int): Option[Result] =
      if(results.successes.size + 1 == childCount) Some(Success) else None
    override def updateResults(results: Results, tickResult: TickResult): Results = results.copy(results.successes :+ tickResult.node)
  }
  case object Fallback extends ControlFlowNode {
    override def deferredResultTypes: Set[Result] = Set(Failure)
    override def checkReturn(results: Results, childCount: Int): Option[Result] =
      if(results.failures.size + 1 == childCount) Some(Failure) else None
    override def updateResults(results: Results, tickResult: TickResult): Results = results.copy(results.failures :+ tickResult.node)
  }
  case class Parallel(threshold: Int) extends ControlFlowNode {
    override def deferredResultTypes: Set[Result] = Set(Success, Failure)
    override def checkReturn(results: Results, childCount: Int): Option[Result] = {
      if(results.successes.size >= threshold) {
        Some(Success)
      } else if(results.failures.size > childCount - threshold) {
        Some(Failure)
      } else {
        None
      }
    }
    override def updateResults(results: Results, tickResult: TickResult): Results = tickResult.result match {
        case Success => results.copy(successes = results.successes :+ tickResult.node)
        case Failure => results.copy(failures = results.failures :+ tickResult.node)
        case Running => results
      }
    }

  case object Decorator extends ControlFlowNode {
    override def deferredResultTypes: Set[Result] = ???
    override def checkReturn(results: Results, childCount: Int): Option[Result] = ???
    override def updateResults(results: Results, tickResult: TickResult): Results = ???
  }

  private def controlFlowNode(nodeType: ControlFlowNode): Behavior[Command] = Behaviors.setup { context =>
    context.log.debug("{} node created", nodeType)

    def awaitingTick: Behavior[Command] = Behaviors.receiveMessage {
      case Tick(parent) =>
        context.log.debug("Received initial tick")
        // tick the first child
        context.children.headOption.map {
          case first: ActorRef[Command] =>
            context.log.debug("Ticking first child {}", first)
            first ! Tick(context.self)
            awaitingChildResult(parent, Results())
        } getOrElse {
          throw new IllegalStateException("A control flow node must have at least one child node")
        }
      case res: TickResult =>
        // if we get this, it means we have a bug
        context.log.warn("Received {} while idle", res)
        awaitingTick
      case CreateChildren(children) =>
        val childRefs = Node.createChildren(children, context)
        children.zip(childRefs).foreach { case (child, childRef) =>
          childRef ! CreateChildren(child.children)
        }
        Behaviors.same

    }

    def awaitingChildResult(parent: ActorRef[TickResult], results: Results): Behavior[Command] = Behaviors.receiveMessage {
      case tickResult @ TickResult(result, child) if nodeType.deferredResultTypes(result) =>
        context.log.debug("Received tick result for deferred result {}", result)
        nodeType.checkReturn(results, context.children.size).map { result =>
          context.log.debug("Returning tick to parent")
          parent ! TickResult(result, context.self)
          awaitingTick
        } getOrElse {
          // tick next
          context.children.dropWhile(_ != child) match {
            case `child` :: (tail: List[ActorRef[Command]]) =>
              context.log.debug("Ticking next child {}", tail.head)
              tail.head ! Tick(context.self)
            case Nil =>
              // nothing more to tick, but we should never end up here
              context.log.warn("Bug")
          }
          awaitingChildResult(parent, nodeType.updateResults(results, tickResult))
        }
      case TickResult(other, _) =>
        context.log.debug("Received tick for other result {}", other)
        parent ! TickResult(other, context.self)
        awaitingTick
      case Tick(_) =>
        // skip it
        context.log.warn("Received tick while waiting for child results. We're ticking too fast!")
        Behaviors.same
    }

    awaitingTick
  }

  private def conditionNode[State](checkCondition: State => Boolean, state: State): Behavior[Command] = Behaviors.setup { context =>
    context.log.debug("Condition node created")

    Behaviors.receiveMessage {
      case Tick(parent) =>
        context.log.debug("Received tick")
        val result = if (checkCondition(state)) Success else Failure
        parent ! TickResult(result, context.self)
        Behaviors.same
      case _ =>
        Behaviors.same
    }
  }

  private def actionNode[State](performAction: (State, Logger) => Future[Result], state: State): Behavior[Command] = Behaviors.setup { context =>
    context.log.debug("Action node created")

    import context.executionContext

    def idleAction: Behavior[Command] = Behaviors.receiveMessage {
      case Tick(parent) =>
        context.log.debug("Received tick")
        val action = performAction(state, context.log)
        val timeout = after(InstantaneousActionTimeout)(Future.successful(ActionNotInstantaneous))(context.system.classicSystem)
        context.pipeToSelf(Future.firstCompletedOf(Seq(action, timeout))) {
          case TrySuccess(result: Result) => ActionResult(result, parent)
          case TrySuccess(ActionNotInstantaneous(parent, action)) => ActionNotInstantaneous(parent, action)
          case TryFailure(_) => ActionNotInstantaneous(parent, action)
        }
        Behaviors.same
      case ActionResult(result, parent) =>
        parent ! TickResult(result, context.self)
        Behaviors.same
      case ActionNotInstantaneous(parent, action) =>
        parent ! TickResult(Running, context.self)
        runningAction(action)

      case _ =>
        Behaviors.same
    }

    def runningAction(action: Future[Result]): Behavior[Command] = Behaviors.receiveMessage {
      case Tick(parent) if action.isCompleted =>
        context.log.debug("Received tick")
        action.foreach { result =>
          parent ! TickResult(result, context.self)
        }
        idleAction
      case Tick(parent) =>
        context.log.debug("Received tick")
        parent ! TickResult(Running, context.self)
        Behaviors.same
      case _ =>
        Behaviors.same
    }

    idleAction
  }

  private def rootNode(root: RootNode): Behavior[Command] = Behaviors.setup { context =>
    context.log.debug("Root node created")
    Behaviors.withTimers { timers =>

      val children = Node.createChildren(root.children, context)
      children.zip(root.children).foreach { case (childRef, child) =>
        childRef ! CreateChildren(child.children)
      }

      // TODO we should wait until the tree is built before ticking
      timers.startTimerAtFixedRate(classOf[Tick], Tick(context.self), TickDuration)

      Behaviors.receiveMessage {
        case Tick(_) =>
          context.log.info("Root tick")
          context.children.foreach { case child: ActorRef[Command] =>
            child ! Tick(context.self)
          }
          Behaviors.same
        case TickResult(result, from) =>
          context.log.debug("Root got {} result from child {}", result, from)
          Behaviors.same
        case CreateChildren(_) =>
          Behaviors.ignore
        }
      }
    }


  sealed trait Node {
    def children: Seq[Node]
  }

  object Node {

    private[behaviortrees] def createChildren(children: Seq[Node], context: ActorContext[Command]): Seq[ActorRef[Command]] = {
      children.map {
        case SequenceNode(_*) =>
          context.spawnAnonymous(controlFlowNode(Sequence))
        case FallbackNode(_*) =>
          context.spawnAnonymous(controlFlowNode(Fallback))
        case ParallelNode(threshold, _*) =>
          context.spawnAnonymous(controlFlowNode(Parallel(threshold)))
        case ConditionNode(checkCondition, state) =>
          context.spawnAnonymous(conditionNode(checkCondition, state))
        case ActionNode(performAction, state) =>
          context.spawnAnonymous(actionNode(performAction, state))
      }
    }

  }
  case class RootNode(children: Node*) extends Node {
    def run(): ActorSystem[Command] = {
      ActorSystem(rootNode(this), "behavior-tree")
    }
  }
  case class SequenceNode(children: Node*) extends Node
  case class FallbackNode(children: Node*) extends Node
  case class ParallelNode(threshold: Int, children: Node*) extends Node
  case class ConditionNode[State](checkCondition: State => Boolean, state: State) extends Node {
    override def children: Seq[Node] = Seq.empty
  }
  case class ActionNode[State](performAction: (State, Logger) => Future[Result], state: State) extends Node {
    override def children: Seq[Node] = Seq.empty
  }

}
