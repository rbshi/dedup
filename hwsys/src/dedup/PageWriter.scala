package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import util.FrgmDemux

case class PageWriterConfig(dataWidth: Int = 512, ptrWidth: Int = 64) {
  val pgIdxWidth = 32
  val pgByteSize = 4096
  val pgAddBitShift = log2Up(pgByteSize)
  val pgWordCnt = pgByteSize / (dataWidth/8)

  val pgIdxFifoSize = 256 // no fence on this stream flow, always assume there's enough space in idxFifo
  val pgBufSize = 16 * pgWordCnt

  val storeBufSize = 16 * pgWordCnt

  val frgmType = Bits(dataWidth bits)
}

case class PageWriterResp(conf: PageWriterConfig) extends Bundle {
  val pgIdx = UInt(conf.pgIdxWidth bits)
  val pgPtr = UInt(conf.ptrWidth bits)
  val isExist = Bool()
}

case class PageWriterIO(conf: PageWriterConfig) extends Bundle {
  val initEn = in Bool()

  val frgmIn   = slave Stream (Fragment(Bits(conf.dataWidth bits)))
  val res = master Stream (PageWriterResp(conf))
  /** interface bloomFilter that priliminarily filters the fragments*/
  val bfRes = slave Stream (Bool())
  /** interface hash table that stores the SHA3 entry of each page */
  val ptrStrm1, ptrStrm2 = master Stream(UInt(conf.ptrWidth bits))
  val lookupRes = slave Stream (HashTabResp(conf.ptrWidth))
  /** interfae storage, TODO: add bandwidth control module*/
  // val axiConf = Axi4ConfigAlveo.u55cHBM
  // val axiStore = master(Axi4(axiConf))
}


class PageWriter(conf: PageWriterConfig) extends Component {

  val io = PageWriterIO(conf)
  val pgIdxCnt = Counter(conf.pgIdxWidth bits, io.frgmIn.lastFire)
  val pgIdxGen = Stream(UInt(conf.pgIdxWidth bits))
  pgIdxGen.payload := pgIdxCnt
  pgIdxGen.valid := True // always valid
  val pgIdxStrms = StreamDemux(pgIdxGen.continueWhen(io.bfRes.fire), io.bfRes.payload.asUInt, 2).map(_.queue(conf.pgIdxFifoSize))

  io.bfRes.throwWhen(io.frgmIn.lastFire)

  /** demux1 with bloom filter res, T: PgBuffer, F: PgWr */
  val frgmDemux1 =  FrgmDemux(2, Bits(conf.dataWidth bits))
  frgmDemux1.io.sel := io.bfRes.payload.asUInt
  frgmDemux1.io.en := io.bfRes.valid
  frgmDemux1.io.strmI << io.frgmIn
  /** two pgWr path (fragment) */
  val pgGoThro = frgmDemux1.io.strmO(0)
  val pgBuffer = frgmDemux1.io.strmO(1).queue(conf.pgBufSize)
  /** demux2 with ht lookup res, T: PgThrow, F: PgWr */
  val frgmDemux2 = FrgmDemux(2, Bits(conf.dataWidth bits))
  frgmDemux2.io.sel := io.lookupRes.isExist.asUInt
  frgmDemux2.io.en := io.lookupRes.valid
  frgmDemux2.io.strmI << pgBuffer

  val pgWr = frgmDemux2.io.strmO(0).queue(conf.pgBufSize)
  val pgThrow = frgmDemux2.io.strmO(1)

  /** frgmArbiter, two PgWr -> pgStore FIXME: add a FIFO before store to avoid the pressure */
  val pgStore = StreamFragmentArbiter(conf.frgmType)(Vec(pgGoThro, pgWr))

  /** store ptr generator*/
  val storePtr = Counter(conf.pgIdxWidth bits, pgStore.fire)

  /** resPtr stream */
  // mux(genPtr, lookupPtr)
  val resPtr = Stream(UInt(conf.pgIdxWidth bits))
  resPtr.payload := (io.lookupRes.isExist & pgBuffer.lastFire) ? io.lookupRes.dupPtr | (storePtr << conf.pgAddBitShift).resized
  // mux idxStream
  val resIdx = StreamMux(pgBuffer.lastFire.asUInt, pgIdxStrms)

  val resVld = pgGoThro.isLast | pgBuffer.isLast
  resPtr.valid := resVld
  resIdx.valid := resVld

  val resJoin = StreamJoin(resIdx, resPtr)
  io.res.translateFrom(resJoin)((a,b) => {
    a.pgIdx := b._1
    a.pgPtr := b._2
    a.isExist := io.lookupRes.isExist & pgBuffer.lastFire
  })

  io.lookupRes.throwWhen(pgBuffer.lastFire)
  io.ptrStrm1.payload := storePtr
  io.ptrStrm2.payload := storePtr
  io.ptrStrm1.valid.setWhen(pgGoThro.lastFire)
  io.ptrStrm2.valid.setWhen(pgWr.lastFire)

  /** page store / throw */
  pgThrow.freeRun()
  pgStore.freeRun()

}
