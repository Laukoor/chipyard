// See LICENSE for license details.

package firechip.bridgeinterfaces

import chisel3._
import freechips.rocketchip.rocket.TraceDoctor

// class TraceDoctor(val traceWidth: Int) extends Bundle {
//   val valid = Bool()
//   val bits = Vec(traceWidth, Bool())
// }


// A per-tile interface that includes the tile's clock and reset
class TileTraceDoctorIO(val traceWidth: Int) extends Bundle {
  val clock: Clock = Clock()
  val reset: Bool  = Bool()
  val data         = new TraceDoctor(traceWidth)
  val tracerVTrigger: Bool = Bool()
}