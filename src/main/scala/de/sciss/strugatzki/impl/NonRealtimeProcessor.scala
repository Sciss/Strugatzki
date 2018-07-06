/*
 *  NonRealtimeProcessor.scala
 *  (Strugatzki)
 *
 *  Copyright (c) 2011-2018 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.strugatzki
package impl

import java.io.RandomAccessFile
import java.nio.ByteBuffer

import de.sciss.file._
import de.sciss.osc.{Bundle, PacketCodec}
import de.sciss.processor.Processor
import de.sciss.processor.impl.ProcessorImpl
import de.sciss.synth.io.{AudioFile, AudioFileSpec, AudioFileType, SampleFormat}
import de.sciss.synth.{Buffer, ControlSet, GE, Server, Synth, SynthDef, ugen}

import scala.concurrent.{ExecutionContext, blocking}

object NonRealtimeProcessor {
  final case class BufferSpec(control: String, numFrames: Int, numChannels: Int = 1)
  final case class RenderConfig(name: String, inFile: File, inSpec: AudioFileSpec,
                                numFeatures: Int, stepSize: Int,
                                blockSize: Int = 64, buffers: List[BufferSpec] = Nil,
                                outFile: Option[File] = None, fixNaNs: Boolean = true)

  final case class ServerFailed(code: Int) extends Exception

  def render[A](config: RenderConfig)(extract: GE => GE)
               (implicit exec: ExecutionContext): Processor[File] = {
    val res = new RenderImpl(config, extract)
    res.start()
    res
  }

  private final class RenderImpl(config: RenderConfig, extract: GE => GE)
    extends ProcessorImpl[File, Processor[File]] with Processor[File] {

    override def toString: String = config.name

    private def mkFile(suffix: String, keep: Boolean = false): File = {
      val f = File.createTemp(prefix = "struga_nrt", suffix = ".aif")
      val p: PartialFunction[Any, Unit] = { case _ => f.delete() }
      if (keep) onFailure(p) else onComplete(p)
      f
    }

    def body(): File = {
      val inChannels        = config.inSpec.numChannels
      val sampleRate        = config.inSpec.sampleRate
      val stepSize          = config.stepSize
      val inFrames          = config.inSpec.numFrames
      val outFile           = config.outFile getOrElse blocking(mkFile(".aif", keep = true))
      val sCfg              = Server.Config()
      val (oscF, dummyOutputF) = blocking {
        (mkFile(".osc"), mkFile(".aif"))
      }
      sCfg.inputBusChannels = inChannels
      sCfg.sampleRate       = sampleRate.toInt
      sCfg.outputBusChannels= 1
      sCfg.nrtCommandPath   = oscF.absolutePath
      sCfg.nrtInputPath     = Some(config.inFile.absolutePath)
      sCfg.nrtOutputPath    = dummyOutputF.absolutePath
      sCfg.nrtHeaderFormat  = AudioFileType.AIFF
      sCfg.nrtSampleFormat  = SampleFormat.Int16

      val s                 = Server.dummy("nrt", sCfg)
      val featBufSize       = 1024
      val featBuf           = Buffer(s)
      val featRate          = sampleRate / stepSize

      val df = SynthDef("nrt") {
        import ugen._
        val chanOff = NumOutputBuses.ir
        val in      = In.ar(chanOff, inChannels)
        val feat    = extract(in)
        val trig    = Impulse.kr(featRate) // - Impulse.kr( 0 )
        val phaseHi = featBufSize - 1
        val phase   = Stepper.kr(trig, 0, 0, phaseHi, 1, phaseHi)
        BufWr.kr(in = feat, buf = featBuf.id, index = phase)
      }

      val syn       = Synth(s)
      val outFrames = ((inFrames + stepSize - 1) / stepSize).toInt // + 1
      val numWrites = (outFrames + featBufSize - 1) / featBufSize

      val tmpNames  = blocking(Array.fill(numWrites)(mkFile(suffix = ".aif")))

      val bufBndls  = Array.tabulate(numWrites) { i =>
        val startFrame  = i * featBufSize
        val stopFrame   = math.min(outFrames, startFrame + featBufSize)
        val numFrames   = stopFrame - startFrame

        val msg         = featBuf.writeMsg(
          path          = tmpNames(i).absolutePath,
          fileType      = AudioFileType.AIFF,
          sampleFormat  = SampleFormat.Float,
          numFrames     = if (i == 0) numFrames - 1 else numFrames,
          startFrame    = if (i == 0) 1 else 0,
          leaveOpen     = false
        )

        // i don't know... in theory i should be writing the half a frame before or after,
        // but that causes trouble. so just write it exactly at the Stepper boundaries
        // and hope it scales up to long durations :-(
        val time = (stopFrame - 0.0) / featRate
        Bundle.secs(time, msg)
      }

      val (userMsgs, userArgs) = config.buffers.map { case BufferSpec(bufName, bufFrames, bufChans) =>
        val buf = Buffer(s)
        val msg = buf.allocMsg(numFrames = bufFrames, numChannels = bufChans)
        val set = ControlSet.Value(key = bufName, value = buf.id)
        msg -> set
      } .unzip

      val renderMsgs = featBuf.allocMsg(featBufSize, config.numFeatures) :: df.recvMsg ::
        syn.newMsg(df.name, s.rootNode, args = userArgs) :: Nil

      val initBndl = Bundle.secs(0.0, userMsgs ::: renderMsgs: _*)

      val bndls = initBndl +: bufBndls // don't bother about n_free and b_free

      val c   = PacketCodec().scsynth().build
      val sz  = bndls.map(_.encodedSize(c)).max
      blocking {
        val raf = new RandomAccessFile(oscF, "rw")
        try {
          val bb  = ByteBuffer.allocate(sz)
          val fch = raf.getChannel
          bndls.foreach { bndl =>
            bndl.encode(c, bb)
            bb.flip()
            raf.writeInt(bb.limit)
            fch.write(bb)
            bb.clear()
          }
        } finally {
          raf.close()
        }
      }

      // val dur = Bundle.timetagToSecs( bufBndls.last.timetag )
      // val dur = bufBndls.last.timetag.toSecs
      val dur = bndls.last.timetag.toSecs

      val nrtFut      = Server.renderNRT(dur, sCfg)
      nrtFut.start()
      val nrtRes: Int = await(nrtFut, weight = 0.8)
      if (nrtRes != 0) throw NonRealtimeProcessor.ServerFailed(nrtRes)

      checkAborted()

      blocking {
        val afOutS  = AudioFileSpec(AudioFileType.AIFF, SampleFormat.Float, config.numFeatures, featRate)
        val afOut   = AudioFile.openWrite(outFile, afOutS)
        try {
          var iw = 0; while(iw < numWrites) {
            checkAborted()
            val afIn  = AudioFile.openRead(tmpNames(iw))
            val b     = try {
              val _b = afIn.buffer(afIn.numFrames.toInt)
              afIn.read(_b)
              _b
            } finally {
              afIn.close()
            }

            if (config.fixNaNs) {
              val lasts = new Array[Float](afIn.numChannels)
              // deal with NaNs
              var ch = 0; while(ch < afIn.numChannels) {
                val cb    = b(ch)
                var last  = lasts(ch)
                var ib = 0; while(ib < cb.length) {
                  val f = cb(ib)
                  if (f.isNaN) cb(ib) = last else last = f
                ib += 1 }
                lasts(ch) = last
              ch += 1 }
            }

            afOut.write(b)
            afIn.file.foreach(_.delete())
            val prog = ((iw + 1).toFloat / numWrites * 0.2f) + 0.8f  // last 20%
            progress = prog
          iw += 1 }
        } finally {
          afOut.close()
        }
      }

      outFile
    }
  }
}