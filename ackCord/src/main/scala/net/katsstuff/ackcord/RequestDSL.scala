/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2017 Katrix
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.katsstuff.ackcord

import scala.language.implicitConversions

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import cats.MonadFilter
import net.katsstuff.ackcord.data.ChannelId
import net.katsstuff.ackcord.http.requests.RESTRequests.GetChannel
import net.katsstuff.ackcord.http.requests.{Request, RequestAnswer, RequestResponse, RequestStreams}

sealed trait RequestDSL[+A] {

  def map[B](f: A => B):                 RequestDSL[B]
  def filter(f: A => Boolean):           RequestDSL[A]
  def flatMap[B](f: A => RequestDSL[B]): RequestDSL[B]

  def run[B, Ctx](flow: Flow[Request[B, Ctx], RequestAnswer[B, Ctx], NotUsed]): Source[A, NotUsed]
}
object RequestDSL {

  def init:                                     RequestDSL[Unit] = Pure(())
  implicit def wrap[A](request: Request[A, _]): RequestDSL[A]    = SingleRequest(request)
  def pure[A](a: A):                            RequestDSL[A]    = Pure(a)
  def maybePure[A](opt: Option[A]):             RequestDSL[A]    = opt.fold[RequestDSL[A]](NoRequest)(Pure.apply)

  implicit val monad: MonadFilter[RequestDSL] = new MonadFilter[RequestDSL] {
    override def map[A, B](fa: RequestDSL[A])(f: A => B):                 RequestDSL[B] = fa.map(f)
    override def flatMap[A, B](fa: RequestDSL[A])(f: A => RequestDSL[B]): RequestDSL[B] = fa.flatMap(f)
    override def filter[A](fa: RequestDSL[A])(f: A => Boolean):           RequestDSL[A] = fa.filter(f)

    override def tailRecM[A, B](a: A)(f: A => RequestDSL[Either[A, B]]): RequestDSL[B] = ???
    override def pure[A](x: A) = Pure(x)
    override def empty[A]: RequestDSL[A] = NoRequest
  }

  private case class Pure[+A](a: A) extends RequestDSL[A] {
    override def map[B](f: A => B):                 RequestDSL[B] = Pure(f(a))
    override def filter(f: A => Boolean):           RequestDSL[A] = if (f(a)) this else NoRequest
    override def flatMap[B](f: A => RequestDSL[B]): RequestDSL[B] = f(a)

    override def run[B, Ctx](flow: Flow[Request[B, Ctx], RequestAnswer[B, Ctx], NotUsed]): Source[A, NotUsed] =
      Source.single(a)
  }

  private case object NoRequest extends RequestDSL[Nothing] {
    override def map[B](f: Nothing => B):                 RequestDSL[B]       = this
    override def filter(f: Nothing => Boolean):           RequestDSL[Nothing] = this
    override def flatMap[B](f: Nothing => RequestDSL[B]): RequestDSL[B]       = this

    override def run[B, Ctx](flow: Flow[Request[B, Ctx], RequestAnswer[B, Ctx], NotUsed]): Source[Nothing, NotUsed] =
      Source.empty[Nothing]
  }

  private case class SingleRequest[A](request: Request[A, _]) extends RequestDSL[A] {
    override def map[B](f: A => B)                 = SingleRequest(request.map(f))
    override def filter(f: A => Boolean)           = SingleRequest(request.filter(f))
    override def flatMap[B](f: A => RequestDSL[B]) = AndThenRequestMonad(this, f)

    override def run[B, Ctx](flow: Flow[Request[B, Ctx], RequestAnswer[B, Ctx], NotUsed]): Source[A, NotUsed] = {
      val casted = flow.asInstanceOf[Flow[Request[A, _], RequestAnswer[A, _], NotUsed]]

      Source.single(request).via(casted).collect {
        case res: RequestResponse[A, _] => res.data
      }
    }
  }

  private case class AndThenRequestMonad[A, +B](request: RequestDSL[A], f: A => RequestDSL[B]) extends RequestDSL[B] {
    override def map[C](g: B => C):                 RequestDSL[C] = AndThenRequestMonad(request, f.andThen(_.map(g)))
    override def filter(g: B => Boolean):           RequestDSL[B] = AndThenRequestMonad(request, f.andThen(_.filter(g)))
    override def flatMap[C](g: B => RequestDSL[C]): RequestDSL[C] = AndThenRequestMonad(this, g)

    override def run[C, Ctx](flow: Flow[Request[C, Ctx], RequestAnswer[C, Ctx], NotUsed]): Source[B, NotUsed] =
      request.run(flow).flatMapConcat(s => f(s).run(flow))
  }
}
object Other {
  val channelId:       ChannelId    = ???
  val token:           String       = ???
  implicit val system: ActorSystem  = ???
  implicit val mat:    Materializer = ???

  import RequestDSL._
  import syntax._
  val operation = for {
    _          <- init
    rawChannel <- GetChannel(channelId)
    channel    <- maybePure(rawChannel.toChannel.flatMap(_.asTGuildChannel))
    _          <- channel.sendMessage(s"Cchannel name: ${channel.name}")
  } yield ()

  operation.run(RequestStreams.simpleRequestFlow(token)).runWith(Sink.ignore)
}
