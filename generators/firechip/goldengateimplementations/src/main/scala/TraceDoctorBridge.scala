// See LICENSE for license details

package firechip.goldengateimplementations

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters

import midas.widgets._
import firesim.lib.bridgeutils._

import firechip.bridgeinterfaces._

/** TraceDoctorBridgeModule - Host 端桥接模块
  * 功能：从 FPGA target 采集 trace 数据，通过 DMA 传输到 host CPU
  * StreamToHostCPU mixin 提供了 DMA 队列接口 (streamEnq)
  * 
  * === 主机端 DMA 访问参数映射 ===
  * 主机端 C++ 代码通过以下参数访问 DMA 流：
  * 
  * 1. **Stream ID** (toHostStreamIdx)
  *    - 来源：StreamToHostCPU.allocate() 自动分配
  *    - 生成的宏：TRACEDOCTORBRIDGEMODULE_<N>_to_cpu_stream_idx
  *    - 作用：标识这个 bridge 的 DMA 通道编号
  * 
  * 2. **Queue Depth** (toHostCPUQueueDepth)
  *    - 定义：下面第 31 行 `val toHostCPUQueueDepth = TOKEN_QUEUE_DEPTH`
  *    - 值：3072（来自 TokenQueueConsts）
  *    - 生成的宏：TRACEDOCTORBRIDGEMODULE_<N>_queue_depth
  *    - 作用：DMA 队列能容纳多少个 token
  * 
  * 3. **Token Width** (BIG_TOKEN_WIDTH)
  *    - 定义：第 66 行 `val discreteDmaWidth = BIG_TOKEN_WIDTH`
  *    - 值：512 bits = 64 bytes
  *    - 生成的宏：TRACEDOCTORBRIDGEMODULE_<N>_token_width
  *    - 作用：每次 DMA 传输的数据块大小
  * 
  * 4. **Trace Width** (traceWidth)
  *    - 定义：第 64 行 `val traceWidth = trace.bits.getWidth`
  *    - 值：运行时计算，取决于 TraceDoctor.bits 的实际宽度
  *    - 生成的宏：TRACEDOCTORBRIDGEMODULE_<N>_trace_width
  *    - 作用：告诉 host 端实际 trace 数据有多少 bits
  * 
  * === 主机端使用示例 ===
  * ```cpp
  * // 读取生成的头文件中的宏定义
  * int stream_idx = TRACEDOCTORBRIDGEMODULE_0_to_cpu_stream_idx;
  * int queue_depth = TRACEDOCTORBRIDGEMODULE_0_queue_depth;
  * int token_width = TRACEDOCTORBRIDGEMODULE_0_token_width;
  * 
  * // 分配 DMA 缓冲区
  * uint64_t* buffer = new uint64_t[queue_depth * token_width / 64];
  * 
  * // 从 DMA 拉取数据
  * pull_to_cpu_stream(stream_idx, buffer, queue_depth);
  * ```
  */
class TraceDoctorBridgeModule(key: TraceDoctorKey)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[TraceDoctorBridgeTargetIO]]()(p)
    with StreamToHostCPU {  // 提供 DMA 传输能力

  // DMA 队列深度配置
  val toHostCPUQueueDepth  = TokenQueueConsts.TOKEN_QUEUE_DEPTH

  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO)  // Golden Gate widget 标准接口
    val hPort = IO(HostPort(new TraceDoctorBridgeTargetIO(key.traceWidth)))  // 从 FPGA target 接收数据的端口

    // 控制寄存器（可从 host 端通过 MMIO 配置）
    val initDone = genWORegInit(Wire(Bool()), "initDone", false.B)  // 初始化完成标志
    val traceEnable = genWORegInit(Wire(Bool()), "traceEnable", false.B)  // trace 采集使能

    // 触发器选择：0=始终触发, 1=使用 tracerVTrigger
    val triggerSelector = RegInit(0.U((p(CtrlNastiKey).dataBits).W))
    attach(triggerSelector, "triggerSelector", WriteOnly) // 将寄存器注册到 控制寄存器表 (Control Register)

    // ========== 数据采集控制逻辑 ==========
    // 从 hPort 提取 trace 数据（包含 valid, bits 等字段）
    val trace = hPort.hBits.tiletrace.data
    // 数据有效性：valid=1 且不在 reset 状态
    val traceValid = trace.valid && !hPort.hBits.tiletrace.reset

    // 触发条件选择器：根据 triggerSelector 决定何时采集
    val trigger = MuxLookup(triggerSelector, false.B, Seq(
      0.U -> true.B,  // 模式0：始终触发
      1.U -> hPort.hBits.tiletrace.tracerVTrigger  // 模式1：等待 TracerV 触发信号
    ))

    // 最终采集条件：初始化完成 && 使能 && 数据有效 && 触发条件满足
    val traceOut = initDone && traceEnable && traceValid && trigger

    // ========== DMA Token 计算 ==========
    // === 主机端 DMA 参数 2：实际数据宽度 ===
    // trace 数据的实际位宽（从 TraceDoctor.bits 获取）
    // 这个值会导出为 C++ 宏 TRACEDOCTORBRIDGEMODULE_<N>_trace_width
    val traceWidth = trace.bits.getWidth
    // DMA 单次传输的 token 宽度（通常是 512 bits）
    val discreteDmaWidth = TokenQueueConsts.BIG_TOKEN_WIDTH
    // 需要多少个 token 才能传输完整的 trace（向上取整）
    val tokensPerTrace = math.max((traceWidth + discreteDmaWidth - 1) / discreteDmaWidth, 1)

    // DMA 缓冲区大小（字节）= token宽度(bits) / 8 * 队列深度
    lazy val dmaSize = BigInt((discreteDmaWidth / 8) * TokenQueueConsts.TOKEN_QUEUE_DEPTH)

    // ========== 多 Token 传输限制 ==========
    // TODO: 下面注释的代码实现了多 token 传输功能
    // 但由于未知原因会导致 FPGA 综合时序变差（Verilator 仿真正常）
    // 目前限制为单 token（512 bits）传输
    
    // 断言：当前只支持单 token 传输（traceWidth <= 512 bits）
    assert(tokensPerTrace == 1)

    // ========== 多 Token 状态机（已禁用）==========
    // 用于控制发送哪个 token 以及是否传输完成的状态机
    // val tokenCounter = new Counter(tokensPerTrace)  // token 计数器
    // val readyNextTrace = WireInit(true.B)  // 是否准备接收下一个 trace
    // when (streamEnq.fire()) {  // 当 DMA 成功发送一个 token 时
    //  readyNextTrace := tokenCounter.inc()  // 计数器递增，判断是否完成
    // }

    // ========== 调试输出 ==========
    // 在综合时打印 trace 宽度和 token 划分信息
    println( "TraceDoctorBridgeModule")
    println(s"    traceWidth      ${traceWidth}")  // trace 实际位宽
    println(s"    dmaTokenWidth   ${discreteDmaWidth}")  // DMA token 位宽（512）
    println(s"    requiredTokens  {")  // 需要几个 token
    for (i <- 0 until tokensPerTrace)  {
      val from = ((i + 1) * discreteDmaWidth) - 1
      val to   = i * discreteDmaWidth
      println(s"        ${i} -> traceBits(${from}, ${to})")  // 每个 token 对应的 bit 范围
    }
    println( "    }")
    println( "")

    // ========== 多 Token 传输实现（已禁用）==========
    // 将 trace 填充到多个 token 的总宽度
    // val paddedTrace = trace.bits.asUInt().pad(tokensPerTrace * discreteDmaWidth)
    // 生成 token 序列：每个 token 对应 trace 的一段 bits
    // val paddedTraceSeq = for (i <- 0 until tokensPerTrace) yield {
    //   i.U -> paddedTrace(((i + 1) * discreteDmaWidth) - 1, i * discreteDmaWidth)
    // }

    // 多 token DMA 传输逻辑（已禁用）
    // streamEnq.valid := hPort.toHost.hValid && traceOut
    // streamEnq.bits := MuxLookup(tokenCounter.value , 0.U, paddedTraceSeq)  // 根据计数器选择当前 token

    // 反压控制：等待当前 trace 的所有 token 都发送完
    // hPort.toHost.hReady := initDone && streamEnq.ready && readyNextTrace

    // ========== DMA 数据传输（核心挂载点）==========
    // 将 trace 数据送入 DMA 队列：当 hPort 有数据且采集条件满足时
    streamEnq.valid := hPort.toHost.hValid && traceOut
    // 将 trace.bits 序列化为 UInt 并填充到 DMA token 宽度（512-bit）
    streamEnq.bits := trace.bits.asUInt.pad(discreteDmaWidth)

    // 反压控制：当 DMA 队列准备好且初始化完成时，接受新数据
    hPort.toHost.hReady := initDone && streamEnq.ready
    // fromHost 方向始终准备接收（本 bridge 不需要 host->target 数据）
    hPort.fromHost.hValid := true.B

    genCRFile()  // 生成控制寄存器文件
    
    // ========== 生成 C++ 头文件内容 ==========
    // === 为什么要生成 C++ 代码？ ===
    // Golden Gate 仿真框架的工作流程：
    // 1. Scala 代码描述 FPGA 硬件（本文件）
    // 2. 编译时调用 genHeader()，将参数写入 StringBuilder
    // 3. Golden Gate 将 StringBuilder 内容嵌入自动生成的 C++ driver 代码
    // 4. C++ driver 编译后，通过这些宏定义获知硬件参数
    //
    // === 生成的不是独立文件，而是嵌入代码 ===
    // 这些宏定义会被**内嵌**到自动生成的 C++ driver 源码中，而不是独立的 .h 文件
    // 具体位置：Golden Gate 生成的 simif_<design>.cc 或类似文件中
    //
    // === 生成的宏定义示例 ===
    // 下面的代码会生成类似这样的 C++ 宏：
    // ```cpp
    // #define TRACEDOCTORBRIDGEMODULE_struct_guard  // 标记此 bridge 存在
    // #define TRACEDOCTORBRIDGEMODULE_0_to_cpu_stream_idx 2
    // #define TRACEDOCTORBRIDGEMODULE_0_to_cpu_stream_depth 3072
    // #define TRACEDOCTORBRIDGEMODULE_0_queue_depth 3072
    // #define TRACEDOCTORBRIDGEMODULE_0_token_width 512
    // #define TRACEDOCTORBRIDGEMODULE_0_trace_width 256
    // #define TRACEDOCTORBRIDGEMODULE_0_clock_domain_name "tile"
    // ```
    //
    // === C++ 使用方式 ===
    // 在 tracedoctor.h 中使用条件编译检查这些宏：
    // ```cpp
    // #ifdef TRACEDOCTORBRIDGEMODULE_struct_guard  // ← 检查 bridge 是否存在
    // class tracedoctor_t {
    //   tracedoctor_t(...) {
    //     int stream_id = TRACEDOCTORBRIDGEMODULE_0_to_cpu_stream_idx;  // ← 使用宏
    //     buffer = malloc(TRACEDOCTORBRIDGEMODULE_0_queue_depth * 64);
    //   }
    // };
    // #endif
    // ```
    //
    // === 关键API ===
    // - genConstStatic(): 生成 #define 宏定义
    // - emitClockDomainInfo(): 生成时钟域相关的宏定义
    // - super.genHeader(): 生成 MMIO 寄存器的地址映射结构体
    //
    override def genHeader(base: BigInt, sb: StringBuilder) {
      import CppGenerationUtils._
      val headerWidgetName = getWName.toUpperCase  // 如 "TRACEDOCTORBRIDGEMODULE_0"
      super.genHeader(base, sb)
      // 导出 DMA 队列深度
      sb.append(genConstStatic(s"${headerWidgetName}_queue_depth", UInt32(TokenQueueConsts.TOKEN_QUEUE_DEPTH)))
      // 导出 token 宽度（512-bit）
      sb.append(genConstStatic(s"${headerWidgetName}_token_width", UInt32(discreteDmaWidth)))
      
      // 3. Trace 宽度：实际 trace 数据的 bit 数（可能 < 512）
      sb.append(genConstStatic(s"${headerWidgetName}_trace_width", UInt32(traceWidth)))
      
      // 4. 时钟域信息：用于多时钟域同步
      // 生成宏：TRACEDOCTORBRIDGEMODULE_<N>_clock_domain_name
      //        TRACEDOCTORBRIDGEMODULE_<N>_clock_multiplier
      //        TRACEDOCTORBRIDGEMODULE_<N>_clock_divisor
      emitClockDomainInfo(headerWidgetName, sb)
      
      // 注：Stream ID (to_cpu_stream_idx) 由 StreamToHostCPU trait 自动生成
    }
  }
}