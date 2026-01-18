// See LICENSE for license details.

package firechip.bridgeinterfaces

import chisel3._

case class TraceDoctorBridgeParams(traceWidth: Int)

class TraceDoctorData(traceWidth: Int) extends Bundle {
  val valid = Bool()
  val bits  = Vec(traceWidth, Bool())
}

class TileTraceDoctorIO(traceWidth: Int) extends Bundle {
  val clock         = Clock()
  val reset         = Bool()
  val data          = new TraceDoctorData(traceWidth)
  val tracerVTrigger = Bool()
}

/** Target-side IO for the TraceDoctor bridge (one instance per tile). */
class TraceDoctorBridgeTargetIO(traceWidth: Int) extends Bundle {
  // 这一条就是从 ChipTop 过来的 TraceDoctor IO
  val tracedoctor = Input(new TileTraceDoctorIO(traceWidth))
}
