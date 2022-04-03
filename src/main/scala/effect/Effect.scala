package effect

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.collection.mutable.Stack

trait Fiber[+A] {
  def join: Effect[A]
}

case class FiberContext[A](startEffect: Effect[A]) extends Fiber[A] {
  // Basic implementation of a fiber
  // uses global ExecutionContext as the underlying ThreadPool

  override def join: Effect[A] =
    Effect.async(callback => await(callback))

  // ************************
  // ** Effect interpreter **
  // ************************

  // type erasure helpers for the imperative implementation
  type Erased         = Effect[Any]
  type ErasedCallback = Any => Any
  type Continuation   = Any => Erased

  private def erase[X](effect: Effect[X]): Erased =
    effect.asInstanceOf[Erased]

  // internal context state

  val stack                 = new Stack[Continuation]() // continuation stack
  var loop                  = true
  var currentEffect: Erased = startEffect

  sealed trait FiberState
  case class Running(callbacks: List[A => Any]) extends FiberState
  case class Done(result: A)                    extends FiberState

  // thread-safe internal state
  val state: AtomicReference[FiberState] =
    new AtomicReference(Running(List.empty))

  // completes the Fiber by calling all pending callbacks
  // if the fiber is already done this is treated is a defect
  private def complete(result: A): Unit = {
    var loop = true
    while (loop) {
      val oldState = state.get
      oldState match {
        case Running(callbacks) =>
          if (state.compareAndSet(oldState, Done(result))) {
            callbacks.foreach(cb => cb(result))
            loop = false
          }
        case Done(_result) =>
          throw new IllegalStateException("Internal defect: effect.Fiber being completed multiple times")
      }
    }
  }

  // adds a completion callback to the internal state
  // or calls it immediately if the fiber is already done
  private def await(callback: A => Any): Unit = {
    var loop = true
    while (loop) {
      val oldState = state.get
      oldState match {
        case Running(callbacks) =>
          val newState = Running(callback :: callbacks)
          loop = !state.compareAndSet(oldState, newState)
        case Done(result) =>
          callback(result)
          loop = false
      }
    }
  }

  private def continueLoopOrComplete(returnValue: Any): Unit =
    if (stack.isEmpty) {
      loop = false
      complete(returnValue.asInstanceOf[A])
    } else {
      val continuation = stack.pop()
      currentEffect = continuation(returnValue)
    }

  // Stack-safe interpreter loop, using an internal continuation stack and a control flag
  // (one easier but more naive approach is to implement it as a recursive interpreter).
  // You can notice that all functional programming niceties are thrown out the window
  // such as type safety and immutability.
  // Fortunately these problems are limited the interpreter implementation.
  private def run(): Unit =
    while (loop)
      currentEffect match {
        case Effect.SucceedNow(value) =>
          continueLoopOrComplete(value)

        case Effect.Succeed(thunk) =>
          continueLoopOrComplete(thunk())

        case Effect.FlatMap(effect, continuation) =>
          stack.push(continuation.asInstanceOf[Continuation])
          currentEffect = erase(effect)

        case Effect.Async(register) =>
          if (stack.isEmpty) {
            loop = false
            register(a => complete(a.asInstanceOf[A]))
          } else {
            loop = false
            register { a =>
              currentEffect = Effect.succeedNow(a)
              loop = true
              run()
            }
          }

        case Effect.Fork(effect) =>
          val fiber = FiberContext(effect)
          continueLoopOrComplete(fiber)
      }

  // submits the interpreter loop to the execution context
  ExecutionContext.global.execute(() => run())
}

sealed trait Effect[+A] { self =>
  def flatMap[B](f: A => Effect[B]): Effect[B] =
    Effect.FlatMap(self, f)

  def map[B](f: A => B): Effect[B] =
    flatMap(f andThen Effect.succeedNow)

  def as[B](value: => B): Effect[B] =
    self.map(_ => value)

  def zip[B](that: => Effect[B]): Effect[(A, B)] =
    zipWith(that)((a, b) => (a, b))

  def zipRight[B](that: => Effect[B]): Effect[B] =
    zipWith(that)((_, b) => b)

  def *>[B](that: => Effect[B]): Effect[B] =
    zipRight(that)

  def zipWith[B, C](that: => Effect[B])(f: (A, B) => C): Effect[C] =
    for {
      a <- self
      b <- that
    } yield f(a, b)

  def forever: Effect[Nothing] =
    self *> self.forever

  def repeat(n: Int): Effect[Unit] =
    if (n <= 0) Effect.succeedNow()
    else self *> repeat(n - 1)

  def fork: Effect[Fiber[A]] =
    Effect.Fork(self)

  def zipPar[B](that: => Effect[B]): Effect[(A, B)] =
    for {
      f <- self.fork
      b <- that
      a <- f.join
    } yield (a, b)

  private def unsafeRunFiber: Fiber[A] =
    FiberContext(self)

  def unsafeRunSync: A = {
    val latch  = new CountDownLatch(1)
    var result = null.asInstanceOf[A]
    self.map { a =>
      result = a
      latch.countDown()
    }.unsafeRunFiber
    latch.await()
    result
  }

}

object Effect {
  // declarative encoding of the functional effect
  case class SucceedNow[A](value: A)                             extends Effect[A]
  case class Succeed[A](f: () => A)                              extends Effect[A]
  case class FlatMap[A, B](effect: Effect[A], f: A => Effect[B]) extends Effect[B]
  case class Fork[A](effect: Effect[A])                          extends Effect[Fiber[A]]
  case class Async[A](register: (A => Any) => Any)               extends Effect[A]

  def succeedNow[A](value: A): Effect[A] =
    SucceedNow(value)

  def succeed[A](value: => A): Effect[A] =
    Succeed(() => value)

  def async[A](register: (A => Any) => Any): Effect[A] =
    Async(register)

  def sleep(time: Duration): Effect[Unit] =
    succeed(Thread.sleep(time.toMillis))

  def collectPar[A](zs: List[Effect[A]]): Effect[List[A]] =
    zs.reverse.foldLeft(succeedNow(List[A]()))((acc, curr) => (acc zipPar curr).map(t => t._2 :: t._1))

  def forEachPar[A, B](f: A => Effect[B])(xs: List[A]): Effect[List[B]] =
    collectPar(xs map f)
}

trait EffectApp {
  def run: Effect[Any]

  def main(args: Array[String]): Unit = {
    val result = run.unsafeRunSync
    println(s"Result is $result")
  }
}
