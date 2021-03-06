// See LICENSE for license details.

package dana

import Chisel._

class SRAMInterface(
  val dataWidth: Int,
  val numReadPorts: Int,
  val numWritePorts: Int,
  val numReadWritePorts: Int,
  val sramDepth: Int
) extends Bundle {
  override def cloneType = new SRAMInterface(
    dataWidth = dataWidth,
    numReadPorts = numReadPorts,
    numWritePorts = numWritePorts,
    numReadWritePorts = numReadWritePorts,
    sramDepth = sramDepth
  ).asInstanceOf[this.type]
  // Data Input
  val din = Vec.fill(numReadWritePorts){UInt(OUTPUT, width = dataWidth)}
  val dinW = Vec.fill(numWritePorts){UInt(OUTPUT, width = dataWidth)}
  // Data Output
  val dout = Vec.fill(numReadWritePorts){UInt(INPUT, width = dataWidth)}
  val doutR = Vec.fill(numReadPorts){UInt(INPUT, width = dataWidth)}
  // Addresses
  val addr = Vec.fill(numReadWritePorts){UInt(OUTPUT, width = log2Up(sramDepth))}
  val addrR = Vec.fill(numReadPorts){UInt(OUTPUT, width = log2Up(sramDepth))}
  val addrW = Vec.fill(numWritePorts){UInt(OUTPUT, width = log2Up(sramDepth))}
  // Write enable
  val we = Vec.fill(numReadWritePorts){Bool(OUTPUT)}
  val weW = Vec.fill(numWritePorts){Bool(OUTPUT)}
}

class SRAM (
  val dataWidth: Int = 8,
  val sramDepth: Int = 64,
  val numReadPorts: Int = 0,
  val numWritePorts: Int = 0,
  val numReadWritePorts: Int = 2,
  val initSwitch: Int = -1,
  val elementsPerBlock: Int = -1
) extends BlackBox {
  if (initSwitch >= 0) {
    setVerilogParameters(
      "#(.WIDTH(" + dataWidth + ")," +
        ".DEPTH(" + sramDepth + ")," +
        ".LG_DEPTH(" + log2Up(sramDepth) + ")," +
        ".INIT_SWITCH(" + initSwitch + ")," +
        ".ELEMENTS_PER_BLOCK(" + elementsPerBlock + "))\n")
    setName("sram_infer_preloaded_cache")}
  else {
    setVerilogParameters(
      "#(.WIDTH(" + dataWidth + ")," +
        ".DEPTH(" + sramDepth + ")," +
        ".LG_DEPTH(" + log2Up(sramDepth) + "))\n ")
    setName("sram")}

  val io = new SRAMInterface(
    numReadPorts = numReadPorts,
    numWritePorts = numWritePorts,
    numReadWritePorts = numReadWritePorts,
    dataWidth = dataWidth,
    sramDepth = sramDepth
  ).flip
  val mem = Mem(sramDepth, UInt(width = dataWidth))
  val buf = Reg(Vec.fill(numReadWritePorts){UInt(width = dataWidth)})
  val bufR = Reg(Vec.fill(numReadPorts){UInt(width = dataWidth)})

  for (i <- 0 until numReadPorts) {
    bufR(i) := mem(io.addrR(i))
    io.doutR(i) := bufR(i)
  }

  for (i <- 0 until numWritePorts) {
    when (io.weW(i)) {
      mem(io.addrW(i)) := io.dinW(i)
    }
  }

  for (i <- 0 until numReadWritePorts) {
    when (io.we(i)) {
      mem(io.addr(i)) := io.din(i)
    }
    buf(i) := mem(io.addr(i))
    io.dout(i) := buf(i)
  }
}

class SRAMSinglePortInterface(
  val dataWidth: Int,
  val sramDepth: Int
) extends Bundle {
  override def cloneType = new SRAMDualPortInterface(
    dataWidth = dataWidth,
    sramDepth = sramDepth).asInstanceOf[this.type]
  val we = Bool(OUTPUT)
  val din = UInt(OUTPUT, width = dataWidth)
  val addr = UInt(OUTPUT, width = log2Up(sramDepth))
  val dout = UInt(INPUT, width = dataWidth)
}

class SRAMDualPortInterface(
  val dataWidth: Int,
  val sramDepth: Int
) extends Bundle {
  override def cloneType = new SRAMDualPortInterface(
    dataWidth = dataWidth,
    sramDepth = sramDepth).asInstanceOf[this.type]
  val we = Vec(2, Bool(OUTPUT))
  val din = Vec(2, UInt(OUTPUT, width = dataWidth))
  val addr = Vec(2, UInt(OUTPUT, width = log2Up(sramDepth)))
  val dout = Vec(2, UInt(INPUT, width = dataWidth))
}

class SRAMDualPort(
  val dataWidth: Int,
  val sramDepth: Int
) extends Module {
  val io = new SRAMDualPortInterface(
    dataWidth = dataWidth,
    sramDepth = sramDepth).flip
  val sram = Module(new SRAM(
    dataWidth = dataWidth,
    numReadPorts = 0,
    numWritePorts = 0,
    numReadWritePorts = 2,
    initSwitch = -1,
    elementsPerBlock = -1,
    sramDepth = sramDepth)).io

  for (i <- 0 until 2) {
    sram.we(i) := io.we(i)
    sram.din(i) := io.din(i)
    sram.addr(i) := io.addr(i)
    io.dout(i) := sram.dout(i)
  }
}

class SRAMTests(uut: SRAM, isTrace: Boolean = true)
    extends Tester(uut, isTrace) {
  // Generate a local copy of the memory in a vector
  val copy = Array.fill(uut.sramDepth){0}
  for (i <- 0 until uut.sramDepth) {
    copy(i) = rnd.nextInt((Math.pow(2, uut.dataWidth) - 1).toInt)
  }
  // Write all the data into the memory
  for (i <- 0 until uut.sramDepth) {
    poke(uut.io.we(0), 1)
    poke(uut.io.addr(0), i)
    poke(uut.io.din(0), copy(i))
    step(1)
    poke(uut.io.we(0), 0)
  }
  // Verify that all the data is correct
  for (i <- 0 until uut.sramDepth) {
    poke(uut.io.addr(1), i)
    step(1)
    expect(uut.io.dout(1), copy(i))
  }
}
