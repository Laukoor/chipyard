// See LICENSE for license details.

package firechip.bridgeinterfaces

import chisel3._
import testchipip.cosim.TileTraceDoctorIO

/** Target-side IO for the TraceDoctor bridge (one instance per tile). */
class TraceDoctorBridgeTargetIO(traceWidth: Int) extends Bundle {
  // 这一条就是从 ChipTop 过来的 TileTraceDoctorIO
  val tiletrace = Input(new TileTraceDoctorIO(traceWidth))

  // 和 TracerV 一样保留 trigger 的握手
  val triggerCredit = Output(Bool())
  val triggerDebit  = Output(Bool())
}
