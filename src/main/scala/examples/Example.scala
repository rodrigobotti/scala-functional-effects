package examples

import scala.concurrent.duration._
import effect.{Effect, EffectApp}

object Console {
  def putStrLn(str: => String) =
    Effect.succeed(println(str))
}

object Example1 extends EffectApp {
  import Console._

  def run = for {
    _ <- putStrLn("worked?")
    _ <- putStrLn("I think so...")
  } yield ()

}

object Example2 extends EffectApp {
  import Console._

  def run =
    putStrLn("hi") *> putStrLn("how") *> putStrLn("are") *> putStrLn("you?")

}

object ExampleRepeat extends EffectApp {
  import Console._
  def run = putStrLn("yo").repeat(100_000)
}

object ExamplePar extends EffectApp {
  import Console._
  import Effect._

  def run = for {
    _ <- putStrLn("1") zipPar putStrLn("2")
    _ <- (sleep(1.seconds) *> putStrLn("x")) zipPar (sleep(1.seconds) *> putStrLn("y"))
    _ <- putStrLn("done")
  } yield ()
}

object ExampleForEachPar extends EffectApp {
  import Console._
  import Effect._

  def waitAndPrint(i: Int): Effect[Int] =
    sleep(1.seconds) *> putStrLn(i.toString) *> succeedNow(i)

  def run =
    forEachPar(waitAndPrint)((0 to 6).toList)

}
