package io.buoyant.grpc.interop

import io.buoyant.test.FunSuite

class InteropTest extends FunSuite {

  val interop = new Client(new Server)

  test("empty_unary") {
    await(interop.emptyUnary())
  }

  test("large_unary") {
    await(interop.largeUnary(Client.DefaultLargeReqSize, Client.DefaultLargeRspSize))
  }

  test("client_streaming") {
    await(interop.clientStreaming(Client.DefaultReqSizes))
  }

  test("server_streaming") {
    await(interop.serverStreaming(Client.DefaultRspSizes))
  }

  ignore("ping_pong") { await(interop.pingPong()) }
  ignore("empty_stream") { await(interop.emptyStream()) }
  ignore("timeout_on_sleeping_server") { await(interop.timeoutOnSleepingServer()) }
  ignore("cancel_after_begin") { await(interop.cancelAfterBegin()) }
  ignore("cancel_after_first_response") { await(interop.cancelAfterFirstResponse()) }
  ignore("status_code_and_message") { await(interop.statusCodeAndMessage()) }

}