// See LICENSE for license details

package firechip.bridgestubs

import chisel3._
import org.chipsalliance.cde.config.Parameters

import firesim.lib.bridgeutils._
import firechip.bridgeinterfaces._
import testchipip.cosim.TileTraceDoctorIO

/** Target-side BlackBox for the TraceDoctor bridge.
  *
  * @param traceWidth  单个 tile trace 的位宽
  */
class TraceDoctorBridge(traceWidth: Int)
    extends BlackBox
    with Bridge[HostPortIO[TraceDoctorBridgeTargetIO]] {

  // 对应 host 端 Scala BridgeModule 的全名
  val moduleName = "firechip.goldengateimplementations.TraceDoctorBridgeModule"

  val io = IO(new TraceDoctorBridgeTargetIO(traceWidth))
  val bridgeIO = HostPort(io)
  // constructorArg 会传给 host 端 BridgeModule 构造函数
  val constructorArg = Some(TraceDoctorBridgeParams(traceWidth))

  // 生成 Golden Gate 需要的注解
  generateAnnotations()
}

object TraceDoctorBridge {
  // 和 WithTraceDoctorBridge HarnessBinder 对接，用 TileTraceDoctorIO 直接建 bridge
  def apply(tileTrace: TileTraceDoctorIO)(implicit p: Parameters): TraceDoctorBridge = {
    // 保证 bridge 在 tile 的 clock/reset 域里
    val bridge = withClockAndReset(tileTrace.clock, tileTrace.reset) {
      Module(new TraceDoctorBridge(tileTrace.traceWidth))
    }
    // 把 testchipip 的 TileTraceDoctorIO 转成 bridgeinterfaces 版本
    bridge.io.tracedoctor := ConvertTileTraceDoctorIO(tileTrace)
    bridge
  }
}
