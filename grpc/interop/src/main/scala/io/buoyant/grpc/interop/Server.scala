package io.buoyant.grpc.interop

import com.twitter.io.Buf
import com.twitter.app.App
import com.twitter.finagle.buoyant.H2
import com.twitter.server.TwitterServer
import com.twitter.util.{Await, Future, Return, Throw, Try}
import grpc.{testing => pb}
import io.buoyant.grpc.runtime.{GrpcStatus, Stream, ServerDispatcher}
import java.net.InetSocketAddress

/**
 * A TestService for interop testing.
 *
 * The interop tests are described here:
 *   https://github.com/grpc/grpc/blob/master/doc/interop-test-descriptions.md
 */
class Server extends pb.TestService {

  def server = pb.TestService.Server(this)
  def dispatcher = ServerDispatcher(server)

  def emptyCall(empty: pb.Empty): Future[pb.Empty] = Future.value(pb.Empty())

  def unaryCall(req: pb.SimpleRequest): Future[pb.SimpleResponse] =
    getStatus(req.responseStatus) match {
      case Some(status) => Future.exception(status)
      case None =>
        // req.responseType match { .. }
        val payload = mkPayload(req.responseSize.getOrElse(0))
        Future.value(pb.SimpleResponse(payload = Some(payload)))
    }

  def cacheableUnaryCall(req: pb.SimpleRequest): Future[pb.SimpleResponse] =
    unaryCall(req)

  /**
   * Echo back each request with a Payload having the requested size
   */
  def fullDuplexCall(
    reqs: Stream[pb.StreamingOutputCallRequest]
  ): Stream[pb.StreamingOutputCallResponse] = {
    val rsps = Stream[pb.StreamingOutputCallResponse]

    def process(): Future[Unit] = reqs.recv().transform {
      case Throw(GrpcStatus.Ok(_)) => rsps.close()
      case Throw(s: GrpcStatus) => Future.exception(s)
      case Throw(e) => Future.exception(GrpcStatus.Internal(e.getMessage))

      case Return(Stream.Releasable(req, release)) =>
        getStatus(req.responseStatus) match {
          case Some(status) =>
            rsps.reset(status)
            Future.exception(status)

          case None =>
            streamResponses(rsps, req.responseParameters)
              .before(release())
              .before(process())
        }
    }

    process()
    rsps
  }

  def halfDuplexCall(
    reqs: Stream[pb.StreamingOutputCallRequest]
  ): Stream[pb.StreamingOutputCallResponse] =
    ??? // Stream.exception(GrpcStatus.Unimplemented("no test cases use halfDuplexCall"))

  /**
   * Returns the aggregated size of input payloads.
   */
  def streamingInputCall(
    reqs: Stream[pb.StreamingInputCallRequest]
  ): Future[pb.StreamingInputCallResponse] =
    accumSize(reqs, 0).map { sz => pb.StreamingInputCallResponse(Some(sz)) }

  private[this] def accumSize(
    reqs: Stream[pb.StreamingInputCallRequest],
    processed: Int
  ): Future[Int] = reqs.recv().transform {
    case Throw(GrpcStatus.Ok(_)) =>
      Future.value(processed)

    case Throw(e) =>
      val s = e match {
        case s: GrpcStatus => s
        case e => GrpcStatus.Internal(e.getMessage)
      }
      Future.exception(e)

    case Return(Stream.Releasable(req, release)) =>
      val sz = req.payload.flatMap(_.body).map(_.length).getOrElse(0)
      release().before(accumSize(reqs, processed + sz))
  }

  /**
   * For each ResponseParameter sent, we return a frame in the stream with the requested size.
   */
  def streamingOutputCall(
    req: pb.StreamingOutputCallRequest
  ): Stream[pb.StreamingOutputCallResponse] = {
    val rsps = Stream[pb.StreamingOutputCallResponse]()
    streamResponses(rsps, req.responseParameters).before(rsps.close())
    rsps
  }

  private[this] def streamResponses(
    rsps: Stream.Provider[pb.StreamingOutputCallResponse],
    params: Seq[pb.ResponseParameters]
  ): Future[Unit] = params match {
    case Nil => Future.Unit
    case Seq(param, tail@_*) =>
      val payload = mkPayload(param.size.getOrElse(0))
      val msg = pb.StreamingOutputCallResponse(Some(payload))
      rsps.send(msg).before(streamResponses(rsps, tail))
  }

  private[this] def getStatus(es: Option[pb.EchoStatus]): Option[GrpcStatus] = es match {
    case None => None
    case Some(pb.EchoStatus(code, msg)) =>
      val status = code match {
        case Some(c) => GrpcStatus(c, msg.getOrElse(""))
        case None => GrpcStatus.Unknown(msg.getOrElse(""))
      }
      Some(status)
  }

  private[this] def mkPayload(sz: Int): pb.Payload = {
    val body = Buf.ByteArray.Owned(Array.fill(sz) { 0.toByte })
    pb.Payload(body = Some(body))
  }
}

object Server extends TwitterServer {

  val addr = flag("addr", new InetSocketAddress(60001), "server address")

  def main() {
    val srvstr = {
      val isa = addr()
      val ip = if (isa.getAddress.isAnyLocalAddress) "" else isa.getHostString
      val port = addr().getPort
      s"${ip}:${port}"
    }

    val server = H2.serve(srvstr, (new Server).dispatcher)
    closeOnExit(server)
    val _ = Await.ready(server)
  }
}