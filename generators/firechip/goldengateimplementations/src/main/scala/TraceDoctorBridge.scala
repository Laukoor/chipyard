// See LICENSE for license details

package firechip.goldengateimplementations

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters

import midas.widgets._
import firesim.lib.bridgeutils._

import firechip.bridgeinterfaces._

class TraceDoctorBridgeModule(key: TraceDoctorBridgeParams)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[TraceDoctorBridgeTargetIO]]()(p)
    with StreamToHostCPU {

  val toHostCPUQueueDepth = TokenQueueConsts.TOKEN_QUEUE_DEPTH

  lazy val module = new BridgeModuleImp(this) {
    val io    = IO(new WidgetIO)
    val hPort = IO(HostPort(new TraceDoctorBridgeTargetIO(key.traceWidth)))

    val initDone    = genWORegInit(Wire(Bool()), "initDone", false.B)
    val traceEnable = genWORegInit(Wire(Bool()), "traceEnable", false.B)

    val triggerSelector = RegInit(0.U(p(CtrlNastiKey).dataBits.W))
    attach(triggerSelector, "triggerSelector", WriteOnly)

    val trace      = hPort.hBits.tracedoctor.data
    val traceValid = trace.valid && !hPort.hBits.tracedoctor.reset

    val trigger = MuxLookup(triggerSelector, false.B, Seq(
      0.U -> true.B,
      1.U -> hPort.hBits.tracedoctor.tracerVTrigger,
    ))

    val traceOut = initDone && traceEnable && traceValid && trigger

    val traceWidth = trace.bits.getWidth
    val tokenWidth = streamEnq.bits.getWidth
    val tokensPerTrace = math.max((traceWidth + tokenWidth - 1) / tokenWidth, 1)
    assert(tokensPerTrace == 1)

    streamEnq.valid := hPort.toHost.hValid && traceOut
    streamEnq.bits := trace.bits.asUInt.pad(tokenWidth)

    hPort.toHost.hReady := initDone && streamEnq.ready
    hPort.fromHost.hValid := true.B

    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(
        base,
        sb,
        "tracedoctor_t",
        "tracedoctor",
        Seq(
          UInt32(toHostStreamIdx),
          UInt32(toHostCPUQueueDepth),
          UInt32(tokenWidth),
          UInt32(traceWidth),
          Verbatim(clockDomainInfo.toC),
        ),
        hasStreams = true,
      )
    }
  }
}
