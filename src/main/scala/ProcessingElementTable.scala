// See LICENSE for license details.

package dana

import Chisel._
import cde.Parameters

class PECacheInterfaceResp(implicit p: Parameters) extends DanaBundle()(p) {
  val field = UInt(width = log2Up(6)) // [TODO] fragile on Dana.scala enum
  val data = UInt(width = bitsPerBlock)
  val peIndex = UInt(width = log2Up(peTableNumEntries))
  val neuronIndex = UInt(width = log2Up(nnConfigNeuronWidth / bitsPerBlock))
}

class PECacheInterfaceReq(implicit p: Parameters) extends DanaBundle()(p) {
  val field = UInt(width = log2Up(6)) // [TODO] fragile on Cache Req Enum
  val data = SInt(width = bitsPerBlock)
  val peIndex = UInt(width = log2Up(peTableNumEntries))
  val cacheIndex = UInt(width = log2Up(cacheNumEntries))
  val cacheAddr = UInt(width =
    log2Up(cacheNumBlocks * elementsPerBlock * elementWidth))
}

class PECacheInterface(implicit p: Parameters) extends DanaBundle()(p) {
  val req = Decoupled(new PECacheInterfaceReq)
  val resp = Decoupled(new PECacheInterfaceResp).flip
}

class PECacheInterfaceLearn(implicit p: Parameters)
    extends PECacheInterface()(p)

class PERegisterFileReq(implicit p: Parameters) extends DanaBundle()(p) {
  // The register index should go down to the element level
  val isWrite = Bool()
  val addr = UInt(width = log2Up(regFileNumElements))
  val peIndex = UInt(width = log2Up(peTableNumEntries))
  val tIdx = UInt(width = log2Up(transactionTableNumEntries))
  val data = UInt(width = elementWidth)
  val location = UInt(width = 1)
  val incWriteCount = Bool()
}

class PERegisterFileReqLearn(implicit p: Parameters)
    extends PERegisterFileReq()(p) {
  val dataBlock = UInt(width = bitsPerBlock)
  val reqType = UInt(width = log2Up(10)) // [TODO] Fragile on Dana.scala
}

class PERegisterFileResp(implicit p: Parameters) extends DanaBundle()(p) {
  val peIndex = UInt(width = log2Up(peTableNumEntries))
  val data = UInt(width = bitsPerBlock)
}

class PERegisterFileRespLearn(implicit p: Parameters)
    extends PERegisterFileResp()(p) {
  val reqType = UInt(width = log2Up(5)) // [TODO] Fragile on Dana.scala
}

class PERegisterFileInterface(implicit p: Parameters) extends DanaBundle()(p) {
  lazy val req = Decoupled(new PERegisterFileReq)
  lazy val resp = Decoupled(new PERegisterFileResp).flip
}

class PERegisterFileInterfaceLearn(implicit p: Parameters)
    extends PERegisterFileInterface()(p) {
  override lazy val req = Decoupled(new PERegisterFileReqLearn)
  override lazy val resp = Decoupled(new PERegisterFileRespLearn).flip
}

class PETransactionTableInterfaceResp(implicit p: Parameters) extends DanaBundle()(p) {
  val peIndex = UInt(width = log2Up(peTableNumEntries))
  val data = UInt(width = bitsPerBlock)
}

class PETableInterface(implicit p: Parameters) extends DanaBundle()(p) {
  val control = (new ControlPETableInterface).flip
  lazy val cache = new PECacheInterface
  lazy val regFile = new PERegisterFileInterface
}

class PETableInterfaceLearn(implicit p: Parameters)
    extends PETableInterface()(p) {
  override lazy val cache = new PECacheInterfaceLearn
  override lazy val regFile = new PERegisterFileInterfaceLearn
}

class ProcessingElementState(implicit p: Parameters) extends DanaBundle()(p) {
  val infoValid = Bool()
  val weightValid = Bool()
  val inValid = Bool() // input_valid
  val tIdx = UInt(width = log2Up(transactionTableNumEntries))
  val cIdx = UInt(width = log2Up(cacheNumEntries))
  val inAddr = UInt(width = log2Up(regFileNumElements))
  val outAddr = UInt(width = log2Up(regFileNumElements))
  // [TODO] learn address may have multiple meanings: 1) this is the
  // current node in the layer and will be used to generate an
  // expected output request to the Register File
  val location = UInt(width = 1)
  val neuronPtr = UInt(width =
    log2Up(elementWidth * elementsPerBlock * cacheNumBlocks))
  val weightPtr = UInt(width =
    log2Up(elementWidth * elementsPerBlock * cacheNumBlocks))
  val decimalPoint = UInt(width = decimalPointWidth)
  val inBlock = UInt(width = bitsPerBlock)
  val weightBlock = UInt(width = bitsPerBlock)
  val numWeights = UInt(width = 8)            // [TODO] fragile
  val activationFunction = UInt(width = activationFunctionWidth)
  val steepness = UInt(width = steepnessWidth)
  val bias = UInt(width = elementWidth)
  val weightoffset = UInt(width = 16)
}

class ProcessingElementStateLearn(implicit p: Parameters)
    extends ProcessingElementState()(p) {
  val learnAddr = UInt(width = log2Up(regFileNumElements))
  val dwAddr = UInt(width = log2Up(regFileNumElements))
  val slopeAddr = UInt(width = log2Up(regFileNumElements))
  val biasAddr = UInt(width = log2Up(regFileNumElements))
  val auxAddr = UInt(width = log2Up(regFileNumElements))
  val inAddrSaved = UInt(width = log2Up(regFileNumElements))
  val weightPtrSaved = UInt(width =
    log2Up(elementWidth * elementsPerBlock * cacheNumBlocks))
  val learnReg = SInt(width = elementWidth)
  val dw_in = SInt(width = elementWidth)
  val errorFunction = UInt(width = log2Up(2)) // [TODO] fragile
  val learningRate = UInt(width = 16)         // [TODO] fragile
  val lambda = UInt(width = 16)               // [TODO] fragile
  val globalWtptr = UInt(width = 16)          // [TODO] fragile
  val numWeightBlocks = UInt(width = 16)
  val stateLearn = UInt(width = log2Up(7))    // [TODO] fragile
  val tType = UInt(width = log2Up(3))         // [TODO] fragile
  val inLast = Bool()
  val inFirst = Bool()
  val batchFirst = Bool()
}

class ProcessingElementTableBase[PeStateType <: ProcessingElementState,
  PeRespType <: ProcessingElementResp, PeIfType <: ProcessingElementInterface,
  PeVecType <: Vec[PeIfType]](
  genPeState: => PeStateType, genPeResp: => PeRespType, genPeVec: => PeVecType)(
  implicit p: Parameters)
    extends DanaModule()(p) {
  lazy val io = new PETableInterface

  // Create the table with the specified top-level parameters. Derived
  // parameters should not be touched.
  val table = Reg(Vec(peTableNumEntries, genPeState))
  // Create the processing elements
  // val pe = Vec(peTableNumEntries, Module(new ProcessingElementLearn()).io)
  val pe = genPeVec

  // Wire up the PE data connections
  for (i <- 0 until peTableNumEntries) {
    pe(i).req.bits.index := UInt(i)
    pe(i).req.bits.decimalPoint := table(i).decimalPoint
    pe(i).req.bits.steepness := table(i).steepness
    pe(i).req.bits.activationFunction := table(i).activationFunction
    pe(i).req.bits.numWeights := table(i).numWeights
    pe(i).req.bits.bias := table(i).bias
    for (j <- 0 until elementsPerBlock) {
      pe(i).req.bits.iBlock(j) :=
        table(i).inBlock(elementWidth * (j + 1) - 1, elementWidth * j)
      pe(i).req.bits.wBlock(j) :=
        table(i).weightBlock(elementWidth * (j + 1) - 1, elementWidth * j)
    }
  }

  def regFileReadReq(addr: UInt, peIndex:UInt, tIdx: UInt, location:UInt) {
    io.regFile.req.valid := Bool(true)
    io.regFile.req.bits.isWrite := Bool(false)
    io.regFile.req.bits.addr := addr
    io.regFile.req.bits.peIndex := peIndex
    io.regFile.req.bits.tIdx := tIdx
    io.regFile.req.bits.location := location
  }

  def regFileWriteReqBase(incWC: Bool, reqType: UInt, addr: UInt, tIdx: UInt,
    data: SInt, location: UInt) {
    io.regFile.req.valid := Bool(true)
    io.regFile.req.bits.isWrite := Bool(true)
    io.regFile.req.bits.incWriteCount := incWC
    io.regFile.req.bits.addr := addr
    io.regFile.req.bits.tIdx := tIdx
    io.regFile.req.bits.data := data
    io.regFile.req.bits.location := location
  }

  def regFileWriteReq(incWC: Bool, reqType: UInt, addr: UInt, tIdx: UInt,
    data: SInt, location: UInt) {
    regFileWriteReqBase(incWC, reqType, addr, tIdx, data, location)
  }

  def fIsFree(x: ProcessingElementInterface): Bool = { x.req.ready }
  val hasFree = pe.exists(fIsFree(_))
  val nextFree = pe.indexWhere(fIsFree(_))

  io.control.req.ready := hasFree

  // Default values for Cache interface
  io.cache.req.valid := Bool(false)
  io.cache.req.bits.field := UInt(0)
  io.cache.req.bits.data := UInt(0)
  io.cache.req.bits.peIndex := UInt(0)
  io.cache.req.bits.cacheIndex := UInt(0)
  io.cache.req.bits.cacheAddr := UInt(0)
  io.cache.resp.ready := Bool(true) // [TODO] placeholder
  // Default values for PE connections
  for (i <- 0 until peTableNumEntries) {
    pe(i).req.valid := Bool(false)
  }
  // Default values for Register File interface
  io.regFile.req.valid := Bool(false)
  io.regFile.req.bits.isWrite := Bool(false)
  io.regFile.req.bits.addr := UInt(0)
  io.regFile.req.bits.peIndex := UInt(0)
  io.regFile.req.bits.tIdx := UInt(0)
  io.regFile.req.bits.data := UInt(0)
  io.regFile.req.bits.location := UInt(0)
  io.regFile.req.bits.incWriteCount := Bool(false)
  io.regFile.resp.ready := Bool(true) // [TOOD] placeholder

  // Deal with inbound requests from the Control module. If we see a
  // request, it can only mean one thing---we need to allocate a PE.
  when (io.control.req.valid) {
    table(nextFree).tIdx := io.control.req.bits.tIdx
    table(nextFree).cIdx := io.control.req.bits.cacheIndex
    table(nextFree).neuronPtr := io.control.req.bits.neuronPointer
    table(nextFree).decimalPoint := io.control.req.bits.decimalPoint
    table(nextFree).inAddr := io.control.req.bits.inAddr
    table(nextFree).outAddr := io.control.req.bits.outAddr
    table(nextFree).location := io.control.req.bits.location
    table(nextFree).numWeights := SInt(-1)
    table(nextFree).weightValid := Bool(false)
    table(nextFree).inValid := Bool(false)
    // Kick the PE
    pe(nextFree).req.valid := Bool(true)
    printfInfo("PETable: Received control request...\n")
    printfInfo("  next free:      0x%x\n", nextFree);
    printfInfo("  tid idx:        0x%x\n", io.control.req.bits.tIdx)
    printfInfo("  cache idx:      0x%x\n", io.control.req.bits.cacheIndex)
    printfInfo("  neuron ptr:     0x%x\n", io.control.req.bits.neuronPointer)
    printfInfo("  decimal:        0x%x\n", io.control.req.bits.decimalPoint)
    printfInfo("  in addr:        0x%x\n", io.control.req.bits.inAddr)
    printfInfo("  out addr:       0x%x\n", io.control.req.bits.outAddr)
  }

  // Inbound requests from the cache. I setup some helper nodes here
  // that interpret the data coming from the cache.
  val cacheRespVec = Wire(Vec(bitsPerBlock/nnConfigNeuronWidth,
    new CompressedNeuron))
  val neuronIndex = io.cache.resp.bits.neuronIndex
  (0 until cacheRespVec.length).map(i =>
    cacheRespVec(i).populate(io.cache.resp.bits.data((i+1) *
      cacheRespVec(i).getWidth - 1,
      i * cacheRespVec(i).getWidth), cacheRespVec(i)))
  // Deal with the cache response if one exists.
  when (io.cache.resp.valid) {
    val peIndex = io.cache.resp.bits.peIndex
    switch (io.cache.resp.bits.field) {
      is (e_CACHE_NEURON) {
        // [TODO] Fragile on increases to widthActivationFunction or
        // widthSteepness.
        table(peIndex).weightPtr := cacheRespVec(neuronIndex).weightPtr
        table(peIndex).numWeights := cacheRespVec(neuronIndex).numWeights
        table(peIndex).activationFunction :=
          cacheRespVec(neuronIndex).activationFunction
        table(peIndex).steepness := cacheRespVec(neuronIndex).steepness
        table(peIndex).bias := cacheRespVec(neuronIndex).bias
        pe(peIndex).req.valid := Bool(true)
        printfInfo("PETable: Weight ptr: 0x%x\n",
          cacheRespVec(neuronIndex).weightPtr)
      }
      is (e_CACHE_WEIGHT) {
        table(peIndex).weightPtr :=
          table(peIndex).weightPtr + UInt(elementsPerBlock * elementWidth / 8)
        table(peIndex).weightBlock := io.cache.resp.bits.data
        // As the weights and inputs can come back in any order, we
        // can only kick the PE if the weights already came back.
        // Otherwise, we just set the weight valid flag and kick the
        // PE when the inputs come back.
        when (table(peIndex).inValid) {
          pe(peIndex).req.valid := Bool(true)
          table(peIndex).weightValid := Bool(false)
          table(peIndex).inValid := Bool(false)
        } .otherwise {
          table(peIndex).weightValid := Bool(true)
        }
        printfInfo("PETable: Valid cache weight resp PE/data 0x%x/0x%x\n",
          peIndex, io.cache.resp.bits.data)
      }
    }
  }

  // Round robin arbitration of PE Table entries
  val peArbiter = Module(new RRArbiter(genPeResp, peTableNumEntries))
  // Wire up the arbiter
  for (i <- 0 until peTableNumEntries) {
    peArbiter.io.in(i).valid := pe(i).resp.valid
    peArbiter.io.in(i).bits.data := pe(i).resp.bits.data
    peArbiter.io.in(i).bits.state := pe(i).resp.bits.state
    peArbiter.io.in(i).bits.index := pe(i).resp.bits.index
    peArbiter.io.in(i).bits.incWriteCount := pe(i).resp.bits.incWriteCount
  }

  // If the arbiter is showing a valid output, then we have to
  // generate some requests based on which PE the arbiter has
  // determined needs something. The action taken depends on the state
  // that we're seeing from the chosen PE.
  when (peArbiter.io.out.valid) {
    switch (peArbiter.io.out.bits.state) {
      is (PE_states('e_PE_GET_INFO)) {
        // Send a request to the cache for information.
        io.cache.req.valid := Bool(true)
        io.cache.req.bits.field := e_CACHE_NEURON
        io.cache.req.bits.peIndex := peArbiter.io.out.bits.index
        io.cache.req.bits.cacheIndex := table(peArbiter.io.out.bits.index).cIdx
        io.cache.req.bits.cacheAddr := table(peArbiter.io.out.bits.index).neuronPtr

        pe(peArbiter.io.out.bits.index).req.valid := Bool(true)
      }
      is (PE_states('e_PE_REQUEST_INPUTS_AND_WEIGHTS)) {
        // All requests are now routed through the Register File (the
        // intermediate storage area for all computation)
        val peIdx = peArbiter.io.out.bits.index
        regFileReadReq(
          table(peIdx).inAddr,
          peIdx,
          table(peIdx).tIdx,
          table(peIdx).location)

        // Send a request to the cache for weights
        io.cache.req.valid := Bool(true)
        io.cache.req.bits.field := e_CACHE_WEIGHT
        io.cache.req.bits.peIndex := peIdx
        io.cache.req.bits.cacheIndex := table(peIdx).cIdx
        io.cache.req.bits.cacheAddr := table(peIdx).weightPtr

        pe(peIdx).req.valid := Bool(true)
      }
      is (PE_states('e_PE_DONE)) {
        // Outputs are always written to the Register File
        regFileWriteReq(
          peArbiter.io.out.bits.incWriteCount,
          e_PE_WRITE_ELEMENT,
          table(peArbiter.io.out.bits.index).outAddr,
          table(peArbiter.io.out.bits.index).tIdx,
          peArbiter.io.out.bits.data,
          table(peArbiter.io.out.bits.index).location)

        pe(peArbiter.io.out.bits.index).req.valid := Bool(true)
      }
    }
  }

  // Assertions

  // Inbound control requests should only happen if there are free
  // entries in the PE Table
  assert(!(!io.control.req.ready && io.control.req.valid),
    "Cache received valid control request when not ready")

  // No PE should have an input and output address equal
  assert(!(io.control.req.valid &&
    (io.control.req.bits.inAddr === io.control.req.bits.outAddr)),
    "PETable saw PE assignment with same input and output address")
}

class ProcessingElementTable(implicit p: Parameters)
    extends ProcessingElementTableBase[ProcessingElementState,
      ProcessingElementResp, ProcessingElementInterface,
      Vec[ProcessingElementInterface]](new ProcessingElementState,
        new ProcessingElementResp,
        Vec(p(PeTableNumEntries), Module(new ProcessingElement).io))(p) {
  when (io.regFile.resp.valid) {
    val peIndex = io.regFile.resp.bits.peIndex
    table(peIndex).inBlock := io.regFile.resp.bits.data
    table(peIndex).inAddr := table(peIndex).inAddr + UInt(elementsPerBlock)
    when (table(peIndex).weightValid) {
      pe(peIndex).req.valid := Bool(true)
      table(peIndex).weightValid := Bool(false)
      table(peIndex).inValid := Bool(false)
    } .otherwise {
      table(peIndex).inValid := Bool(true)
    }
    printfInfo("PETable: Valid RegFile input resp PE/data 0x%x/0x%x\n",
      peIndex, io.regFile.resp.bits.data)
  }
}

class ProcessingElementTableLearn(implicit p: Parameters)
    extends ProcessingElementTableBase[ProcessingElementStateLearn,
      ProcessingElementRespLearn, ProcessingElementInterfaceLearn,
      Vec[ProcessingElementInterfaceLearn]](new ProcessingElementStateLearn,
        new ProcessingElementRespLearn,
        Vec(p(PeTableNumEntries), Module(new ProcessingElementLearn).io))(p) {
  override lazy val io = new PETableInterfaceLearn

  def regFileReadReq(addr: UInt, peIndex:UInt, tIdx: UInt,
    location:UInt, reqType: UInt) {
    regFileReadReq(addr, peIndex, tIdx, location) // From PE Table Base
    io.regFile.req.bits.reqType := reqType
  }

  override def regFileWriteReq(incWC: Bool, reqType: UInt, addr: UInt, tIdx: UInt,
    data: SInt, location: UInt) {
    regFileWriteReqBase(incWC, reqType, addr, tIdx, data, location)
    io.regFile.req.bits.reqType := reqType
  }

  io.regFile.req.bits.reqType := UInt(0)
  io.regFile.req.bits.dataBlock := UInt(0)

  // Register File Block Writeback Table -- This table keeps track of
  // the highest index delta--weight _block_ that has been written.
  // This is needed to know when a a block is the first to be written
  // back. If it is not the first, then it should be accumulated.
  val regFileBlockWbTable = Reg(Vec.fill(transactionTableNumEntries){
    UInt(log2Up(regFileNumElements))})
  (0 until transactionTableNumEntries).map(i =>
    regFileBlockWbTable(i) := regFileBlockWbTable(i))

  for (i <- 0 until peTableNumEntries) {
    pe(i).req.bits.errorFunction := table(i).errorFunction
    pe(i).req.bits.learningRate := table(i).learningRate
    pe(i).req.bits.lambda := table(i).lambda
    pe(i).req.bits.stateLearn := table(i).stateLearn
    pe(i).req.bits.tType := table(i).tType
    pe(i).req.bits.inLast := table(i).inLast
    pe(i).req.bits.inFirst := table(i).inFirst
    pe(i).req.bits.learnReg := table(i).learnReg
    pe(i).req.bits.dw_in := table(i).dw_in
  }

  when (io.control.req.valid) {
    table(nextFree).errorFunction := io.control.req.bits.errorFunction
    table(nextFree).learningRate := io.control.req.bits.learningRate
    table(nextFree).lambda := io.control.req.bits.lambda
    table(nextFree).globalWtptr := io.control.req.bits.globalWtptr
    table(nextFree).numWeightBlocks := io.control.req.bits.numWeightBlocks
    table(nextFree).stateLearn := io.control.req.bits.stateLearn
    table(nextFree).tType := io.control.req.bits.tType
    table(nextFree).inLast := io.control.req.bits.inLast
    table(nextFree).inFirst := io.control.req.bits.inFirst
    table(nextFree).batchFirst := io.control.req.bits.batchFirst
    table(nextFree).learnAddr := io.control.req.bits.learnAddr
    table(nextFree).dwAddr := io.control.req.bits.dwAddr
    table(nextFree).slopeAddr := io.control.req.bits.slopeAddr
    table(nextFree).biasAddr := io.control.req.bits.biasAddr
    table(nextFree).auxAddr := io.control.req.bits.auxAddr
    when (io.control.req.bits.resetWB) {
      regFileBlockWbTable(io.control.req.bits.tIdx) := UInt(0)
    }
    table(nextFree).inAddrSaved := io.control.req.bits.inAddr
    printfInfo("  error func:     0x%x\n", io.control.req.bits.errorFunction)
    printfInfo("  learn rate:     0x%x\n", io.control.req.bits.learningRate)
    printfInfo("  lambda:         0x%x\n", io.control.req.bits.lambda)
    printfInfo("  Global wtptr:   0x%x\n", io.control.req.bits.globalWtptr)
    printfInfo("  learn addr:     0x%x\n", io.control.req.bits.learnAddr)
    printfInfo("  DW addr:        0x%x\n", io.control.req.bits.dwAddr)
    printfInfo("  slope addr:     0x%x\n", io.control.req.bits.slopeAddr)
    printfInfo("  bias addr:      0x%x\n", io.control.req.bits.biasAddr)
    printfInfo("  aux addr:       0x%x\n", io.control.req.bits.auxAddr)
    printfInfo("  stateLearn:     0x%x\n", io.control.req.bits.stateLearn)
    printfInfo("  tType:          0x%x\n", io.control.req.bits.tType)
    printfInfo("  inLast:         0x%x\n", io.control.req.bits.inLast)
    printfInfo("  inFirst:        0x%x\n", io.control.req.bits.inFirst)
    printfInfo("  batchFirst:     0x%x\n", io.control.req.bits.batchFirst)
    printfInfo("  in addr saved:  0x%x\n", io.control.req.bits.inAddr)
  }

  when (io.cache.resp.valid) {
    val peIndex = io.cache.resp.bits.peIndex
    switch (io.cache.resp.bits.field) {
      is (e_CACHE_NEURON) {
        val weightOffset = (cacheRespVec(neuronIndex).weightPtr -
          table(peIndex).globalWtptr) >>
          (UInt(log2Up(elementWidth / 8))) // [TODO] possibly fragile
        table(peIndex).weightPtrSaved := cacheRespVec(neuronIndex).weightPtr
        table(peIndex).weightoffset:= weightOffset
        printfInfo("PETable: weightoffset 0x%x\n", weightOffset)
      }
      is (e_CACHE_WEIGHT_ONLY) {
        table(peIndex).weightPtr :=
          table(peIndex).weightPtr + UInt(elementsPerBlock * elementWidth / 8)
        table(peIndex).weightBlock := io.cache.resp.bits.data
        pe(peIndex).req.valid := Bool(true)
        printfInfo("PETable: Valid cache weight resp PE/data 0x%x/0x%x\n",
          peIndex, io.cache.resp.bits.data)
      }
    }
  }

  when (io.regFile.resp.valid) {
    val peIndex = io.regFile.resp.bits.peIndex
    switch (io.regFile.resp.bits.reqType) {
      is (e_PE_REQ_INPUT) {
        table(peIndex).inBlock := io.regFile.resp.bits.data
        when ((table(peIndex).stateLearn === e_TTABLE_STATE_LEARN_WEIGHT_UPDATE) ||
          (table(peIndex).stateLearn === e_TTABLE_STATE_LEARN_ERROR_BACKPROP)) {
          table(peIndex).dwAddr := table(peIndex).dwAddr + UInt(elementsPerBlock)
          table(peIndex).auxAddr := table(peIndex).auxAddr + UInt(elementsPerBlock)
        } .otherwise {
          table(peIndex).inAddr := table(peIndex).inAddr + UInt(elementsPerBlock)
        }
        when (table(peIndex).weightValid) {
          pe(peIndex).req.valid := Bool(true)
          table(peIndex).weightValid := Bool(false)
          table(peIndex).inValid := Bool(false)
        } .otherwise {
          table(peIndex).inValid := Bool(true)
        }
        printfInfo("PETable: Valid RegFile input resp PE/data 0x%x/0x%x\n",
          peIndex, io.regFile.resp.bits.data)
      }
      is (e_PE_REQ_EXPECTED_OUTPUT) {
        val addr = table(peIndex).learnAddr(log2Up(elementsPerBlock)-1,0)
        val dataVec = Vec((0 until elementsPerBlock).map(i =>
          (io.regFile.resp.bits.data)(elementWidth * (i + 1) - 1, elementWidth * i)))
        table(peIndex).learnReg := dataVec(addr)
        pe(peIndex).req.valid := Bool(true)
        printfInfo("PETable: Valid RegFile E[out] resp PE/data 0x%x/0x%x\n",
          peIndex, io.regFile.resp.bits.data)
        printfInfo("         learnReg -> dataVec(0x%x): 0x%x\n", addr,
          dataVec(addr))
      }
      is(e_PE_REQ_OUTPUT){
        val addr = table(peIndex).inAddr(log2Up(elementsPerBlock)-1,0)
        val dataVec = Vec((0 until elementsPerBlock).map(i =>
          (io.regFile.resp.bits.data)(elementWidth * (i + 1) - 1, elementWidth * i)))
        table(peIndex).learnReg := dataVec(addr)
        pe(peIndex).req.valid := Bool(true)
        printfInfo("PETable: Valid RegFile out resp PE/data 0x%x/0x%x\n",
          peIndex, io.regFile.resp.bits.data)
        printfInfo("         learnReg -> dataVec(0x%x): 0x%x\n", addr,
          dataVec(addr))
      }
      is(e_PE_REQ_DELTA_WEIGHT_PRODUCT){
        val addr = table(peIndex).outAddr(log2Up(elementsPerBlock)-1,0)
        val dataVec = Vec((0 until elementsPerBlock).map(i =>
          (io.regFile.resp.bits.data)(elementWidth * (i + 1) - 1, elementWidth * i)))
        table(peIndex).dw_in := dataVec(addr)
        pe(peIndex).req.valid := Bool(true)
        printfInfo("PETable: Valid RegFile delta--weight resp PE/data 0x%x/0x%x\n",
          peIndex, io.regFile.resp.bits.data)
        printfInfo("         input delta weight product -> dataVec(0x%x): 0x%x\n",
          addr, dataVec(addr))
      }
      is (e_PE_REQ_BIAS) {
        val addr = table(peIndex).outAddr(log2Up(elementsPerBlock)-1,0)
        val dataVec = Vec((0 until elementsPerBlock).map(i =>
          (io.regFile.resp.bits.data)(elementWidth * (i + 1) - 1, elementWidth * i)))
        table(peIndex).dw_in := dataVec(addr)
        pe(peIndex).req.valid := Bool(true)
        printfInfo("PETable: Valid RegFile bias resp PE/data 0x%x/0x%x\n",
          peIndex, io.regFile.resp.bits.data)
        printfInfo("         bias put in dw_in -> dataVec(0x%x): 0x%x\n",
          addr, dataVec(addr))
      }
    }
  }

  for (i <- 0 until peTableNumEntries) {
    peArbiter.io.in(i).bits.dataBlock := pe(i).resp.bits.dataBlock
    peArbiter.io.in(i).bits.error := pe(i).resp.bits.error
    peArbiter.io.in(i).bits.resetWeightPtr := pe(i).resp.bits.resetWeightPtr
  }

  val biasIndex = table(peArbiter.io.out.bits.index).neuronPtr(
    log2Up(bitsPerBlock) - 3 - 1, log2Up(64) - 3)
  val biasUpdateVec = Wire(Vec.fill(elementsPerBlock){UInt(width=elementWidth)})
  biasUpdateVec := UInt(0)
  biasUpdateVec(biasIndex * UInt(2) + UInt(1)) := peArbiter.io.out.bits.data

  val biasAddrLSBs = table(peArbiter.io.out.bits.index).biasAddr(
    log2Up(elementsPerBlock)-1,0)
  val biasUpdateVecSlope = Wire(Vec.fill(elementsPerBlock){UInt(width=elementWidth)})
  biasUpdateVecSlope := UInt(0)
  biasUpdateVecSlope(biasAddrLSBs) := peArbiter.io.out.bits.data

  when (peArbiter.io.out.valid) {
    val peIdx = peArbiter.io.out.bits.index
    // Reset the weight pointer if the PE is telling us to do so
    when (peArbiter.io.out.bits.resetWeightPtr) {
      table(peIdx).weightPtr := table(peIdx).weightPtrSaved
    }
    // The request type is function of PE state
    switch (peArbiter.io.out.bits.state) {
      is (PE_states('e_PE_REQUEST_INPUTS_AND_WEIGHTS)) {
        // All requests are now routed through the Register File (the
        // intermediate storage area for all computation)
        when (table(peIdx).stateLearn === e_TTABLE_STATE_LEARN_ERROR_BACKPROP) {
          regFileReadReq(
            table(peIdx).auxAddr,
            peIdx,
            table(peIdx).tIdx,
            table(peIdx).location,
            e_PE_REQ_INPUT)
        } .elsewhen (table(peIdx).stateLearn === e_TTABLE_STATE_LEARN_WEIGHT_UPDATE) {
          when (table(peIdx).tType === e_TTYPE_BATCH) {
            regFileReadReq(
              table(peIdx).slopeAddr + table(peIdx).weightoffset,
              peIdx,
              table(peIdx).tIdx,
              table(peIdx).location,
              e_PE_REQ_INPUT)
            table(peIdx).slopeAddr := table(peIdx).slopeAddr +
              UInt(elementsPerBlock)
          } .otherwise {
            regFileReadReq(
              table(peIdx).dwAddr,
              peIdx,
              table(peIdx).tIdx,
              table(peIdx).location,
              e_PE_REQ_INPUT)
          }
        } .otherwise {
          regFileReadReq(
            table(peIdx).inAddr,
            peIdx,
            table(peIdx).tIdx,
            table(peIdx).location,
            e_PE_REQ_INPUT)
        }

        // Send a request to the cache for weights
        io.cache.req.valid := Bool(true)
        io.cache.req.bits.field := e_CACHE_WEIGHT
        io.cache.req.bits.peIndex := peIdx
        io.cache.req.bits.cacheIndex := table(peIdx).cIdx
        io.cache.req.bits.cacheAddr := table(peIdx).weightPtr

        pe(peIdx).req.valid := Bool(true)
      }
      is (PE_states('e_PE_DONE)) {
        // Reset the weightPtr and inAddr as we may be using this again
        table(peIdx).inAddr := table(peIdx).inAddrSaved
      }
      is (PE_states('e_PE_REQUEST_EXPECTED_OUTPUT)) {
        regFileReadReq(
          table(peArbiter.io.out.bits.index).learnAddr,
          peArbiter.io.out.bits.index,
          table(peArbiter.io.out.bits.index).tIdx,
          table(peArbiter.io.out.bits.index).location,
          e_PE_REQ_EXPECTED_OUTPUT)
        pe(peArbiter.io.out.bits.index).req.valid := Bool(true)
      }
      is (PE_states('e_PE_ERROR_BACKPROP_REQUEST_WEIGHTS)) {
        // Send a request to the cache for weights
        io.cache.req.valid := Bool(true)
        io.cache.req.bits.field := e_CACHE_WEIGHT_ONLY
        io.cache.req.bits.peIndex := peArbiter.io.out.bits.index
        io.cache.req.bits.cacheIndex := table(peArbiter.io.out.bits.index).cIdx
        // The weightPtr has been reset in the e_PE_DONE state
        io.cache.req.bits.cacheAddr := table(peArbiter.io.out.bits.index).weightPtr

        pe(peArbiter.io.out.bits.index).req.valid := Bool(true)
      }
      is (PE_states('e_PE_ERROR_BACKPROP_WEIGHT_WB)) {
        val tIdx = table(peIdx).tIdx
        val addrWB = table(peIdx).dwAddr
        // Send a request to the Register File to writeback the
        // partial weight block. If this is the first weight block
        // that we're writing back, then we need to tell the Register
        // File to do a write without an accumulate.
        io.regFile.req.valid := Bool(true)
        io.regFile.req.bits.isWrite := Bool(true)
        io.regFile.req.bits.incWriteCount := Bool(false)
        when (addrWB > regFileBlockWbTable(tIdx)) {
          regFileBlockWbTable(tIdx) := addrWB
          io.regFile.req.bits.reqType := e_PE_WRITE_BLOCK_NEW
          printfInfo("PE Table: _WEIGHT_WB reqType/datablock 0x%x/0x%x\n",
            e_PE_WRITE_BLOCK_NEW, peArbiter.io.out.bits.dataBlock.toBits)
        } .otherwise {
          io.regFile.req.bits.reqType := e_PE_WRITE_BLOCK_ACC
          printfInfo("PE Table: _WEIGHT_WB reqType/datablock 0x%x/0x%x\n",
            e_PE_WRITE_BLOCK_ACC, peArbiter.io.out.bits.dataBlock.toBits)
        }
        io.regFile.req.bits.addr := addrWB
        io.regFile.req.bits.tIdx := tIdx
        io.regFile.req.bits.dataBlock := peArbiter.io.out.bits.dataBlock.toBits
        io.regFile.req.bits.location := table(peIdx).location

        table(peIdx).dwAddr := table(peIdx).dwAddr + UInt(elementsPerBlock)
        pe(peIdx).req.valid := Bool(true)
      }
      is(PE_states('e_PE_REQUEST_OUTPUTS_ERROR_BACKPROP)){
        regFileReadReq(
          table(peArbiter.io.out.bits.index).inAddr,
          peArbiter.io.out.bits.index,
          table(peArbiter.io.out.bits.index).tIdx,
          table(peArbiter.io.out.bits.index).location,
          e_PE_REQ_OUTPUT)

        pe(peArbiter.io.out.bits.index).req.valid := Bool(true)
      }
      is(PE_states('e_PE_REQUEST_DELTA_WEIGHT_PRODUCT_ERROR_BACKPROP)){
        regFileReadReq(
          table(peArbiter.io.out.bits.index).outAddr,
          peArbiter.io.out.bits.index,
          table(peArbiter.io.out.bits.index).tIdx,
          table(peArbiter.io.out.bits.index).location,
          e_PE_REQ_DELTA_WEIGHT_PRODUCT)

        pe(peArbiter.io.out.bits.index).req.valid := Bool(true)
      }
      is(PE_states('e_PE_WEIGHT_UPDATE_WRITE_BACK)) {
        // Send an element-wise increment block-write to the cache
        io.cache.req.valid := Bool(true)
        io.cache.req.bits.field := e_CACHE_WEIGHT_WB
        io.cache.req.bits.peIndex := peArbiter.io.out.bits.index
        io.cache.req.bits.cacheIndex := table(peArbiter.io.out.bits.index).cIdx
        io.cache.req.bits.cacheAddr := table(peArbiter.io.out.bits.index).weightPtr -
          UInt(elementsPerBlock * elementWidth / 8)
        io.cache.req.bits.data := peArbiter.io.out.bits.dataBlock.toBits
        printfInfo("PE Table: weight block 0x%x\n",
          peArbiter.io.out.bits.dataBlock.toBits)

        pe(peArbiter.io.out.bits.index).req.valid := Bool(true)
      }
      is (PE_states('e_PE_WEIGHT_UPDATE_WRITE_BIAS)) {
        // Construct a bias update and send it to the cache
        io.cache.req.valid := Bool(true)
        io.cache.req.bits.field := e_CACHE_WEIGHT_WB
        io.cache.req.bits.peIndex := peArbiter.io.out.bits.index
        io.cache.req.bits.cacheIndex := table(peArbiter.io.out.bits.index).cIdx
        io.cache.req.bits.cacheAddr := table(peArbiter.io.out.bits.index).neuronPtr
        io.cache.req.bits.data := biasUpdateVec.toBits
        printfInfo("PE Table: Trying to write bias biasIndex/bias 0x%x/0x%x\n",
          biasIndex, biasUpdateVec.toBits)
        printfInfo("          .data: 0x%x\n", peArbiter.io.out.bits.data)

        // Send a dummy write to the register file so it kicks the
        // Transaction Table
        io.regFile.req.valid := Bool(true)
        io.regFile.req.bits.isWrite := Bool(true)
        io.regFile.req.bits.peIndex := peArbiter.io.out.bits.index
        io.regFile.req.bits.reqType := e_PE_INCREMENT_WRITE_COUNT
        io.regFile.req.bits.incWriteCount := Bool(true)
        io.regFile.req.bits.location := table(peArbiter.io.out.bits.index).location

        pe(peArbiter.io.out.bits.index).req.valid := Bool(true)
      }
      is(PE_states('e_PE_SLOPE_WB)){
        io.regFile.req.valid := Bool(true)
        io.regFile.req.bits.isWrite := Bool(true)
        io.regFile.req.bits.incWriteCount := Bool(false)
        io.regFile.req.bits.peIndex := peArbiter.io.out.bits.index
        // [TODO] This needs to be updated for when we do a batch
        // update with more than one input--output pair
        when (table(peIdx).batchFirst) {
          io.regFile.req.bits.reqType := e_PE_WRITE_BLOCK_NEW
        } .otherwise {
          io.regFile.req.bits.reqType := e_PE_WRITE_BLOCK_ACC
        }
        io.regFile.req.bits.addr := table(peIdx).slopeAddr + table(peIdx).weightoffset
        io.regFile.req.bits.tIdx := table(peIdx).tIdx
        io.regFile.req.bits.dataBlock := peArbiter.io.out.bits.dataBlock.toBits
        io.regFile.req.bits.location := table(peIdx).location
        table(peIdx).slopeAddr := table(peIdx).slopeAddr + UInt(elementsPerBlock)

        table(peIdx).dwAddr := table(peIdx).dwAddr + UInt(elementsPerBlock)
        pe(peIdx).req.valid := Bool(true)
      }
      is (PE_states('e_PE_SLOPE_BIAS_WB)) {
        io.regFile.req.valid := Bool(true)
        io.regFile.req.bits.isWrite := Bool(true)
        io.regFile.req.bits.incWriteCount := Bool(true)
        io.regFile.req.bits.peIndex := peArbiter.io.out.bits.index
        // [TODO] This needs to be an increment update in the event
        // that we're in batch mode and not in the first item
        when (table(peIdx).batchFirst) {
          io.regFile.req.bits.reqType := e_PE_WRITE_ELEMENT
        } .otherwise {
          io.regFile.req.bits.reqType := e_PE_WRITE_BLOCK_ACC
        }
        io.regFile.req.bits.data := peArbiter.io.out.bits.data
        io.regFile.req.bits.dataBlock := biasUpdateVecSlope.toBits
        printfInfo("PE Table: bias wb slope: 0x%x\n",
          biasUpdateVecSlope.toBits)

        io.regFile.req.bits.addr := table(peIdx).biasAddr
        io.regFile.req.bits.tIdx := table(peIdx).tIdx
        io.regFile.req.bits.location := table(peIdx).location

        pe(peIdx).req.valid := Bool(true)
      }
      is (PE_states('e_PE_WEIGHT_UPDATE_REQUEST_BIAS)) {
        io.regFile.req.valid := Bool(true)
        io.regFile.req.bits.isWrite := Bool(false)
        io.regFile.req.bits.peIndex := peArbiter.io.out.bits.index
        io.regFile.req.bits.reqType := e_PE_REQ_BIAS
        io.regFile.req.bits.addr := table(peIdx).biasAddr
        io.regFile.req.bits.tIdx := table(peIdx).tIdx
        io.regFile.req.bits.data := peArbiter.io.out.bits.data
        io.regFile.req.bits.location := table(peIdx).location

        pe(peIdx).req.valid := Bool(true)
      }
    }
  }
}
