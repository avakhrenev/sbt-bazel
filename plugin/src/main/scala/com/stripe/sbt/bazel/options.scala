package com.stripe.sbt.bazel

import cats._
import cats.implicits._
import com.stripe.sbt.bazel.BazelAst.PyExpr
import sbt.TaskKey
import sbt.Keys.Classpath

sealed trait ExprF[V, A]
object ExprF extends ExprFInstances {
  def showAlgebra[V: Show]: ExprF[V, String] => String = {
    case Value       (name) => name.show
    case Union       (x, y) => s"($x ∪ $y)"
    case Difference  (x, y) => s"($x - $y)"
    case Intersection(x, y) => s"($x ∩ $y)"
  }

  def evalAlgebra[A]: ExprF[Set[A], Set[A]] => Set[A] = {
    case Value       (a)    => a
    case Union       (x, y) => x.union(y)
    case Difference  (x, y) => x.diff(y)
    case Intersection(x, y) => x.intersect(y)
  }

  def mapAlgebra[A, B](f: A => B): ExprF[A, Expr[B]] => Expr[B] = {
    case Value       (a)    => Mu.embed(Value(f(a)))
    case Union       (x, y) => Mu.embed(Union(x, y))
    case Difference  (x, y) => Mu.embed(Difference(x, y))
    case Intersection(x, y) => Mu.embed(Intersection(x, y))
  }
}

final case class Value[V, A](value: V) extends ExprF[V, A]
final case class Union[V, A](x: A, y: A) extends ExprF[V, A]
final case class Intersection[V, A](x: A, y: A) extends ExprF[V, A]
final case class Difference[V, A](x: A, y: A) extends ExprF[V, A]

sealed trait Source
object Source {
  case class Evaluate(taskKey: TaskKey[Classpath]) extends Source
  case object Empty extends Source

  implicit val showSource: Show[Source] = Show.fromToString
}

final class ExprOps[V](val x: Expr[V]) extends AnyVal {
  def +(y: Expr[V]): Expr[V] = Mu.embed(Union(x, y))
  def -(y: Expr[V]): Expr[V] = Mu.embed(Difference(x, y))
  def ∪(y: Expr[V]): Expr[V] = Mu.embed(Union(x, y))
  def ∩(y: Expr[V]): Expr[V] = Mu.embed(Intersection(x, y))
}

private[bazel] sealed trait ExprFInstances {
  implicit def functorExprF[V]: Functor[ExprF[V, ?]] =
    new Functor[ExprF[V, ?]] {
      def map[A, B](fa: ExprF[V, A])(f: A => B): ExprF[V, B] =
        fa match {
          case v: Value[_, B @unchecked] => v
          case Union       (x, y)        => Union       (f(x), f(y))
          case Intersection(x, y)        => Intersection(f(x), f(y))
          case Difference  (x, y)        => Difference  (f(x), f(y))
        }
    }
}

sealed trait BazelDslF[+A]
object BazelDslF {
  case class YoloString[A](str: String, next: A) extends BazelDslF[A]
  case class WorkspacePrelude[A](next: A) extends BazelDslF[A]
  case class MavenBindings[A](next: A) extends BazelDslF[A]
  case class BuildPrelude[A](next: A) extends BazelDslF[A]
  case class BuildTargets[A](next: A) extends BazelDslF[A]
  case object Empty extends BazelDslF[Nothing]
}

class BazelDSLOps(val x: BazelDsl) extends AnyVal {
  import BazelDsl.appendAlgebra
  def +:(y: BazelDsl): BazelDsl = x.apply(appendAlgebra(y))
}

object BazelDsl {
  import BazelDslF._

  def appendAlgebra(next: BazelDsl): BazelDslF[BazelDsl] => BazelDsl = {
    case YoloString(s, n)       => Mu.embed(YoloString(s, n))
    case WorkspacePrelude(n)    => Mu.embed(WorkspacePrelude(n))
    case MavenBindings(n)       => Mu.embed(MavenBindings(n))
    case BuildPrelude(n)        => Mu.embed(BuildPrelude(n))
    case BuildTargets(n)        => Mu.embed(BuildTargets(n))
    case Empty                  => next
  }

  def pyExprAlgebra(
    mvnBindings: List[PyExpr],
    buildTargets: List[PyExpr],
    bazelVersion: String
  ): BazelDslF[Vector[BazelAst.PyExpr]] => Vector[BazelAst.PyExpr] = {
    case YoloString(s, n)       => n :+ BazelAst.PyYoloString(s)
    case WorkspacePrelude(n)    => n ++ BazelAst.Helpers.workspacePrelude(bazelVersion)
    case MavenBindings(n)       => n ++ mvnBindings
    case BuildPrelude(n)        => n ++ BazelAst.Helpers.buildPrelude
    case BuildTargets(n)        => n ++ buildTargets
    case Empty                  => Vector.empty[BazelAst.PyExpr]
  }
}