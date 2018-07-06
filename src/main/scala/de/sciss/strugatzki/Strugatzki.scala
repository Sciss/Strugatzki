/*
 *  Strugatzki.scala
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

import java.io.IOException
import java.text.{DecimalFormat, NumberFormat}
import java.util.Locale

import de.sciss.file._
import de.sciss.processor.{Processor, ProcessorFactory}
import de.sciss.span.Span
import de.sciss.strugatzki.FeatureExtraction.{Config => ExtrConfig}
import de.sciss.synth.io.{AudioFile, AudioFileSpec, AudioFileType, SampleFormat}
import scopt.OptionParser

import scala.collection.breakOut
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.duration.Duration
import scala.concurrent.{Promise, Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object Strugatzki {
  import ExecutionContext.Implicits.global

  final val NormalizeName = "feat_norms.aif"
  var tmpDir: File        = file(sys.props.getOrElse("java.io.tmpdir", "/tmp"))
  final val name          = "Strugatzki"

  private lazy val decibelFormat = {
    val res = NumberFormat.getInstance(Locale.US)
    res match {
      case d: DecimalFormat =>
        d.setGroupingUsed(false)
        d.setMinimumFractionDigits(1)
        d.setMaximumFractionDigits(1)
        d.setNegativeSuffix(" dB")
        d.setPositiveSuffix(" dB")

      case _ =>
    }
    res
  }

  private lazy val percentFormat = {
    val res = NumberFormat.getPercentInstance(Locale.US)
    res match {
      case d: DecimalFormat =>
        d.setGroupingUsed(false)
        d.setMinimumFractionDigits(1)
        d.setMaximumFractionDigits(1)

      case _ =>
    }
    res
  }

  def main(args: Array[String]): Unit = {
    var which = ""

    val parser = new OptionParser[Unit](name) {
      opt[Unit]('f', "feature"        ) text "Feature extraction"                            action { (_,_) => which = "feat"  }
      opt[Unit]('c', "correlate"      ) text "Find best correlation with database"           action { (_,_) => which = "corr"  }
      opt[Unit]('s', "segmentation"   ) text "Find segmentation breaks with a file"          action { (_,_) => which = "segm"  }
      opt[Unit]('x', "selfsimilarity" ) text "Create an image of the self similarity matrix" action { (_,_) => which = "self"  }
      opt[Unit]('y', "crosssimilarity") text "Create a cross-similarity vector file"         action { (_,_) => which = "cross" }
      opt[Unit]("stats")                text "Statistics from feature database"              action { (_,_) => which = "stats" }
    }
    if (parser.parse(args.take(1))) {
      val argsRem = args.drop(1)
      which match {
        case "feat"   => featurePre  (argsRem)
        case "stats"  => featureStats(argsRem)
        case "corr"   => featureCorr (argsRem)
        case "segm"   => featureSegm (argsRem)
        case "self"   => featureSelf (argsRem)
        case "cross"  => featureCross(argsRem)
        case _        =>
          parser.showUsage
          sys.exit(1)
      }
    } else sys.exit(1) // parser.showUsage
  }

  private def go(factory: ProcessorFactory { type Repr <: Future[_]})(config: factory.Config)
                (observer: factory.Observer): Unit = {
    val proc = factory.run(config)(observer)
    Await.ready[Any](proc, Duration.Inf)
  }

  def featureCorr(args: Array[String]): Unit = {
    var dir           = file("")
    var verbose       = false
    var punchInStart  = Double.NaN
    var punchInStop   = Double.NaN
    var tempIn        = 0.5
    var punchOutStart = Option.empty[Double]
    var punchOutStop  = Option.empty[Double]
    var tempOut       = 0.5
    var minPunch      = Double.NaN
    var maxPunch      = Double.NaN
    var inFile        = file("")
    var maxBoost      = 8.0
    var numMatches    = 1
    var numPerFile    = 1
    var minSpacing    = 0.0 // 0.5
    var normalize     = true

    implicit val parser: OptionParser[Unit] = new OptionParser[Unit](s"$name -c") {
      opt[Unit]('v', "verbose") text "Verbose output" action { (_,_) => verbose = true }
      opt[File]('d', "dir") required() text "Database directory" action { (f,_) => dir = f }
      opt[Double]("in-start") required() text "Punch in begin (secs)" action { (d,_) => punchInStart = d }
      opt[Double]("in-stop" ) required() text "Punch in end (secs)"   action { (d,_) => punchInStop  = d }
      opt[Double]("in-temp") text "Temporal weight for punch in (0 to 1, default 0.5)" action { (d,_) => tempIn = d }
      opt[Double]("out-start") text "Punch out begin (secs)" action { (d,_) => punchOutStart = Some(d) }
      opt[Double]("out-stop" ) text "Punch out end (secs)"   action { (d,_) => punchOutStop  = Some(d) }
      opt[Double]("out-temp") text "Temporal weight for punch out (0 to 1, default 0.5)" action { (d,_) => tempOut = d }
      opt[Double]("dur-min") required() text "Minimum fill duration (secs)" action { (d,_) => minPunch = d }
      opt[Double]("dur-max") required() text "Maximum fill duration (secs)" action { (d,_) => maxPunch = d }
      opt[Double]("boost-max") text "Maximum loudness boost factor (default 8)" action { (d,_) => maxBoost = d }
      opt[Int]('m', "num-matches") text "Maximum number of matches (default 1)" action { (i,_) => numMatches = i }
      opt[Int]("num-per-file") text "Maximum matches per single file (default 1)" action { (i,_) => numPerFile = i }
      opt[Double]("spacing") text "Minimum spacing between matches within one file (default 0.0)" action { (d,_) => minSpacing = d }
      arg[File]("input") required() text "Meta file of input to process" action { (f,_) => inFile = f }
      opt[Unit]("no-norm") text "Do not apply feature normalization" action { (_,_) => normalize = false }
    }

    if (!parser.parse(args)) sys.exit(1)

    val metaIn = FeatureExtraction.Config.fromXMLFile(inFile)
    val inSpec = AudioFile.readSpec(metaIn.audioInput)

    def secsToFrames(s: Double) = (s * inSpec.sampleRate + 0.5).toLong

    val (ok, punchOutO) = (punchOutStart, punchOutStop) match {
      case (Some(poStart), Some(poStop)) =>
        val outSpan = Span(secsToFrames(poStart), secsToFrames(poStop))
        require(outSpan.length > 0, "Punch out span is empty")
        true -> Some(FeatureCorrelation.Punch(outSpan, tempOut.toFloat))

      case (None, None) => true -> None
      case _            => false -> None
    }
    if (ok) {
      val inSpan = Span(secsToFrames(punchInStart), secsToFrames(punchInStop))
      require(inSpan.length > 0, "Punch in span is empty")
      val punchIn = FeatureCorrelation.Punch(inSpan, tempIn.toFloat)
      val minFrames = secsToFrames(minPunch)
      require(minFrames > 0, "Minimum duration is zero")
      val maxFrames = secsToFrames(maxPunch)
      require(maxFrames >= minFrames, "Maximum duration is smaller than minimum duration")

      FeatureCorrelation.verbose = verbose
      val con = FeatureCorrelation.Config()
      con.databaseFolder  = dir
      con.punchIn         = punchIn
      con.punchOut        = punchOutO
      con.metaInput       = inFile
      con.minPunch        = minFrames
      con.maxPunch        = maxFrames
      con.normalize       = normalize
      con.maxBoost        = maxBoost.toFloat
      con.numMatches      = numMatches
      con.numPerFile      = numPerFile
      con.minSpacing      = secsToFrames(minSpacing)

      import Processor._
      var lastProg = 0
      go(FeatureCorrelation)(con) {
        case Result(_, Success(res)) if res.nonEmpty =>
          println("  Success.")

          res.foreach { m =>
            val s1 = s"""
                        |File      ${m.file.absolutePath}
                        |Similarity: ${toPercentStr(m.sim)}
                        |Span start: ${m.punch.start}
                        |Boost in  : ${toDBStr(m.boostIn)}""".stripMargin
            println(s1)
            if (punchOutO.isDefined) {
              val s2 = s"""Span stop : ${m.punch.stop}
                          |Boost out : ${toDBStr(m.boostOut)}""".stripMargin
              println(s2)
            }
          }
          println()

        case Result(_, Success(_)) =>
          println("  No matches found.")
        case Result(_, Failure(Aborted())) =>
          println("  Aborted")
        case Result(_, Failure(e)) =>
          println("  Failed: ")
          e.printStackTrace()
        case Progress(_, p) =>
          val i = (p * 25).toInt
          while (lastProg < i) {
            print("#")
            lastProg += 1
          }
      }
    }
  }

  private def ampToDB     (amp: Double) = 20 * math.log10(amp)
  private def toPercentStr(d  : Double) = percentFormat.format(d)
  private def toDBStr     (amp: Double) = decibelFormat.format(ampToDB(amp))

  def featureSegm(args: Array[String]): Unit = {
    var dirOption     = Option.empty[File]
    var verbose       = false
    var corrLen       = 0.5
    var temp          = 0.5
    var spanStart     = Option.empty[Double]
    var spanStop      = Option.empty[Double]
    var numBreaks     = 1
    var minSpacing    = 0.2
    var inFile        = file("")
    var normalize     = true

    implicit val parser: OptionParser[Unit] = new OptionParser[Unit](s"$name -s") {
      opt[Unit]('v', "verbose") text "Verbose output" action { (_,_) => verbose = true }
      opt[File]('d', "dir") text "Database directory (required for normalization file)" action { (f,_) => dirOption = Some(f) }
      opt[Double]("length") text "Correlation length in secs (default: 0.5)" action { (d,_) => corrLen = d }
      opt[Double]("temp") text "Temporal weight (0 to 1, default 0.5)" action { (d,_) => temp = d }
      opt[Double]("span-start") text "Search begin in file (secs)" action { (d,_) => spanStart = Some(d) }
      opt[Double]("span-stop" ) text "Search end in file (secs)"   action { (d,_) => spanStop  = Some(d) }
      opt[Int]('m', "num-breaks") text "Maximum number of breaks (default 1)" action { (i,_) => numBreaks = i }
      opt[Double]("spacing") text "Minimum spacing between matches within one file (default 0.2)" action { (d,_) => minSpacing = d }
      arg[File]("input") required() text "Meta file of input to process" action { (f,_) => inFile = f }
      opt[Unit]("no-norm") text "Do not apply feature normalization" action { (_,_) => normalize = false }
    }

    if (!parser.parse(args)) sys.exit(1)

    val metaIn = FeatureExtraction.Config.fromXMLFile(inFile)
    val inSpec = AudioFile.readSpec(metaIn.audioInput)

    def secsToFrames(s: Double) = (s * inSpec.sampleRate + 0.5).toLong

    val span = (spanStart, spanStop) match {
      case (Some(start) , Some(stop)) => Span     (secsToFrames(start), secsToFrames(stop))
      case (Some(start) , None      ) => Span.from(secsToFrames(start))
      case (None        , Some(stop)) => Span.until                    (secsToFrames(stop))
      case (None        , None      ) => Span.All
    }
    require(span.nonEmpty, "Span is empty")
    val corrFrames = secsToFrames(corrLen)
    require(corrFrames > 0, "Correlation duration is zero")

    FeatureSegmentation.verbose = verbose
    val con             = FeatureSegmentation.Config()
    con.metaInput       = inFile
    con.span            = span
    con.corrLen         = corrFrames
    con.temporalWeight  = temp.toFloat
    con.normalize       = normalize
    con.numBreaks       = numBreaks
    con.minSpacing      = secsToFrames(minSpacing)

    if (normalize) dirOption match {
      case Some(dir) =>
        con.databaseFolder = dir
      case _ => exit1()
    }

    import Processor._
    var lastProg = 0
    go(FeatureSegmentation)(con) {
      case Result(_, Success(res)) if res.nonEmpty =>
        println("  Success.")

        res.foreach { b =>
          println(s"""
                     |Similarity: ${toPercentStr(b.sim)}
                     |Position:   ${b.pos}""".stripMargin)
        }
        println()

      case Result(_, Success(_)) =>
        println("  No breaks found.")
      case Result(_, Failure(Aborted())) =>
        println("  Aborted")
      case Result(_, Failure(e)) =>
        println("  Failed: ")
        e.printStackTrace()
      case Progress(_, p) =>
        val i = (p * 25).toInt
        while (lastProg < i) {
          print("#")
          lastProg += 1
        }
    }
  }

  def featureSelf(args: Array[String]): Unit = {
    import de.sciss.strugatzki.SelfSimilarity.{ColorScheme, Config, PsychoOptical}
    var dirOption     = Option.empty[File]
    var verbose       = false
    var corrLen       = 1.0
    var decim         = 1
    var temp          = 0.5
    var spanStart     = Option.empty[Double]
    var spanStop      = Option.empty[Double]
    var inFile        = file("")
    var inFile2       = Option.empty[File]
    var outFile       = file("")
    var colorWarp     = 1.0
    var colorCeil     = 1.0
    var colors        = PsychoOptical: ColorScheme
    var colorInv      = false
    var normalize     = true

    implicit val parser: OptionParser[Unit] = new OptionParser[Unit](s"$name -x") {
      opt[Unit]('v', "verbose") text "Verbose output" action { (_,_) => verbose = true }
      opt[File]('d', "dir") text "Database directory (required for normalization file)" action { (f,_) => dirOption = Some(f) }
      opt[Double]("length") text "Correlation length in secs (default: 1.0)" action { (d,_) => corrLen = d }
      opt[Double]("temp") text "Temporal weight (0 to 1, default 0.5)" action { (d,_) => temp = d }
      opt[Double]("span-start") text "Correlation begin in file (secs)" action { (d,_) => spanStart = Some(d) }
      opt[Double]("span-stop" ) text "Correlation end in file (secs)"   action { (d,_) => spanStop  = Some(d) }
      opt[String]('c', "colors") text "Color scale (gray|psycho ; defaults to 'psycho')" action { (s,_) => colors = ColorScheme(s) }
      opt[Double]("color-warp") text "Color scale warping factor (default: 1.0)" action { (d,_) => colorWarp = d }
      opt[Double]("color-ceil") text "Color scale input ceiling (default: 1.0)"  action { (d,_) => colorCeil = d }
      opt[Unit]('i', "color-inv") text "Inverted color scale" action { (_,_) => colorInv = true }
      opt[Int]('m', "decim") text "Pixel decimation factor (default: 1)" action { (i,_) => decim = i }
      arg[File]("input" ) required() text "Meta file of input to process" action { (f,_) => inFile  = f }
      arg[File]("output") required() text "Image output file"             action { (f,_) => outFile = f }
      opt[Unit]("no-norm") text "Do not apply feature normalization" action { (_,_) => normalize = false }
      opt[File]("input2") text "Second meta input file for cross- instead of self-similarity" action { (f,_) => inFile2 = Some(f) }
    }

    if (!parser.parse(args)) sys.exit(1)

    val metaIn  = FeatureExtraction.Config.fromXMLFile(inFile)
    val inSpec  = AudioFile.readSpec(metaIn.audioInput)

    def secsToFrames(s: Double) = (s * inSpec.sampleRate + 0.5).toLong

    val span = (spanStart, spanStop) match {
      case (Some(start) , Some(stop)) => Span(secsToFrames(start), secsToFrames(stop))
      case (Some(start) , None      ) => Span.from(secsToFrames(start))
      case (None        , Some(stop)) => Span.until(secsToFrames(stop))
      case (None        , None      ) => Span.all
    }
    require(span.nonEmpty, "Span is empty")
    val corrFrames = secsToFrames(corrLen)
    require(corrFrames > 0, "Correlation duration is zero")

    SelfSimilarity.verbose = verbose
    val con             = Config()
    con.metaInput       = inFile
    con.metaInput2      = inFile2
    con.imageOutput     = outFile
    con.span            = span
    con.corrLen         = corrFrames
    con.decimation      = decim
    con.temporalWeight  = temp.toFloat
    con.colors          = colors
    con.colorWarp       = colorWarp.toFloat
    con.colorCeil       = colorCeil.toFloat
    con.colorInv        = colorInv
    con.normalize       = normalize

    if (normalize) dirOption match {
      case Some(dir) =>
        con.databaseFolder = dir
      case _ => exit1()
    }

    import Processor._
    var lastProg = 0
    go(SelfSimilarity)(con) {
      case Result(_, Success(_)) =>
        println("  Done.")
        println()
      case Result(_, Failure(Aborted())) =>
        println("  Aborted")
      case Result(_, Failure(e)) =>
        println("  Failed: ")
        e.printStackTrace()
      case Progress(_, p) =>
        val i = (p * 25).toInt
        while (lastProg < i) {
          print("#")
          lastProg += 1
        }
    }
  }

  def featureStats(args: Array[String]): Unit = {
    var dir       = file("")
    var verbose   = false

    implicit val parser: OptionParser[Unit] = new OptionParser[Unit](s"$name --stats") {
      opt[Unit]('v', "verbose") text "Verbose output" action { (_,_) => verbose = true }
      opt[File]('d', "dir") required() text "Database directory" action { (f,_) => dir = f }
    }
    if (!parser.parse(args)) sys.exit(1)

    println( "Starting stats... " )
    val paths = dir.children(_.name.endsWith("_feat.aif"))
    import Processor._
    var lastProg = 0
    go(FeatureStats)(paths) {
      case Result(_, Success(spans)) =>
        println("  Success.")
        val afNorm = AudioFile.openWrite(dir / NormalizeName,
          AudioFileSpec(AudioFileType.AIFF, SampleFormat.Float, spans.size, 44100))
        try {
          val b = afNorm.buffer(2)
          spans.zipWithIndex.foreach {
            case ((min, max), i) =>
              b(i)(0) = min.toFloat
              b(i)(1) = max.toFloat
          }
          afNorm.write(b)
        } finally {
          afNorm.close()
        }
        println("Done.")
      case Result(_, Failure(Aborted())) =>
        println("  Aborted")
      case Result(_, Failure(e)) =>
        println("  Failed: ")
        e.printStackTrace()
      case Progress(_, p) =>
        val i = (p * 25).toInt
        while (lastProg < i) {
          print("#")
          lastProg += 1
        }
    }
  }

  private def exit1[A]()(implicit p: OptionParser[A]): Nothing = {
    p.showUsage
    sys.exit(1)
  }

  def featurePre(args: Array[String]): Unit = {
    var inputs      = Vec.empty[File]
    var dir         = file("")
    var verbose     = false
    var chanString  = "mix"

    implicit val parser: OptionParser[Unit] = new OptionParser[Unit](s"$name -f") {
      opt[Unit]('v', "verbose") text "Verbose output" action { (_,_) => verbose = true }
      arg[File]("<input>...") required() unbounded() text "List of input files or directories" action { (f,_) => inputs +:= f }
      opt[File]('d', "dir") required() text "Target directory" action { (f,_) => dir = f }
      opt[String]('c', "channels") text "Channel mode (mix|first|last ; defaults to 'mix')" action { (s,_) => chanString = s }
    }
    if (!parser.parse(args)) sys.exit(1)

    if (inputs.isEmpty) exit1()

    import de.sciss.strugatzki.FeatureExtraction.ChannelsBehavior
    val chanMode: ChannelsBehavior = chanString.toLowerCase match {
      case "mix"    => ChannelsBehavior.Mix
      case "first"  => ChannelsBehavior.First
      case "last"   => ChannelsBehavior.Last
      case _        => exit1()
    }

    FeatureExtraction.verbose = verbose
    val inFiles: List[File] = inputs.flatMap { f =>
      if (f.isFile) {
        f :: Nil
      } else if (f.isDirectory) {
        f.children(f => try {
          AudioFile.identify(f).isDefined
        } catch {
          case _: IOException => false
        })
      } else {
        sys.error(s"Not a valid input: $f")
      }
    } (breakOut)

    val targetDir         = dir
    val con               = ExtrConfig()
    con.channelsBehavior  = chanMode

    val p = Promise[Boolean]()

    def iter(list: List[File]): Unit =
      list match {
        case head :: tail =>
          val name1 = {
            val n = head.getName
            val i = n.lastIndexOf('.')
            if (i >= 0) n.substring(0, i) else n
          }
          con.audioInput    = head
          con.featureOutput =      new File(targetDir, s"${name1}_feat.aif")
          con.metaOutput    = Some(new File(targetDir, s"${name1}_feat.xml"))
          feature(con) { success =>
            println(s"success = $success - tail? ${tail.nonEmpty}")
            if (success && tail.nonEmpty) iter(tail) else p.success(success)
          }
        case _ =>
      }

    // idiotic -- first time `go` returns, VM exits otherwise
//    val t = new Thread {
//      override def run(): Unit = synchronized(this.wait())
//      start()
//    }

    iter(inFiles)
    val ok = Await.result(p.future, Duration.Inf)
    sys.exit(if (ok) 0 else 1)
  }

  def featureCross(args: Array[String]): Unit = {
    var dirOption     = Option.empty[File]
    var verbose       = false
    var temp          = 0.5
    var inFile1       = file("")
    var inFile2       = file("")
    var spanStart1    = Option.empty[Double]
    var spanStop1     = Option.empty[Double]
    var spanStart2    = Option.empty[Double]
    var spanStop2     = Option.empty[Double]
    var outFile       = file("")
    var normalize     = true
    var maxBoost      = 8.0

    implicit val parser: OptionParser[Unit] = new OptionParser[Unit](s"$name -y") {
      opt[Unit]('v', "verbose") text "Verbose output" action { (_,_) => verbose = true }
      opt[File]('d', "dir") text "Database directory (required for normalization file)" action { (f,_) => dirOption = Some(f) }
      opt[Double]("temp") text "Temporal weight (0 to 1, default 0.5)" action { (d,_) => temp = d }
      opt[Double]("span1-start") text "Correlation begin in first file (secs)"  action { (d,_) => spanStart1 = Some(d) }
      opt[Double]("span1-stop" ) text "Correlation end in first file (secs)"    action { (d,_) => spanStop1  = Some(d) }
      opt[Double]("span2-start") text "Correlation begin in second file (secs)" action { (d,_) => spanStart2 = Some(d) }
      opt[Double]("span2-stop" ) text "Correlation end in second file (secs)"   action { (d,_) => spanStop2  = Some(d) }
      arg[File]("input1") required() text "Meta file of first input to process"  action { (f,_) => inFile1 = f }
      arg[File]("input2") required() text "Meta file of second input to process" action { (f,_) => inFile2 = f }
      arg[File]("output") required() text "Audio output file" action { (f,_) => outFile = f }
      opt[Double]("boost-max") text "Maximum loudness boost factor (default 8)" action { (d,_) => maxBoost = d }
      opt[Unit]("no-norm") text "Do not apply feature normalization" action { (_,_) => normalize = false }
    }

    if (!parser.parse(args)) sys.exit(1)

    if (normalize && dirOption.isEmpty) {
      Console.err.println(s"Either choose --no-norm or specify a database --dir.")
      sys.exit(1)
    }

    val metaIn1 = FeatureExtraction.Config.fromXMLFile(inFile1)
    val inSpec1 = AudioFile.readSpec(metaIn1.audioInput)
    val metaIn2 = FeatureExtraction.Config.fromXMLFile(inFile2)
    val inSpec2 = AudioFile.readSpec(metaIn2.audioInput)

    def secsToFrames(s: Double)(implicit spec: AudioFileSpec) = (s * spec.sampleRate + 0.5).toLong

    def mkSpan(start: Option[Double], stop: Option[Double])
              (implicit spec: AudioFileSpec): Span.NonVoid = (start, stop) match {
      case (Some(startS), Some(stopS) ) => Span(secsToFrames(startS), secsToFrames(stopS))
      case (Some(startS), None        ) => Span.from (secsToFrames(startS))
      case (None        , Some(stopS) ) => Span.until(secsToFrames(stopS ))
      case (None        , None        ) => Span.all
    }

    val span1 = mkSpan(spanStart1, spanStop1)(inSpec1)
    val span2 = mkSpan(spanStart2, spanStop2)(inSpec2)

    CrossSimilarity.verbose = verbose
    val con             = CrossSimilarity.Config()
    dirOption.foreach(con.databaseFolder = _)
    con.metaInput1      = inFile1
    con.metaInput2      = inFile2
    con.normalize       = normalize
    con.maxBoost        = maxBoost.toFloat
    con.audioOutput     = outFile
    // con.audioOutputType = automatically
    con.span1           = span1
    con.span2           = span2
    con.temporalWeight  = temp.toFloat

    import Processor._
    var lastProg = 0
    go(CrossSimilarity)(con) {
      case Result(_, Success(_)) =>
        println("  Success.")
      case Result(_, Failure(Aborted())) =>
        println("  Aborted")
      case Result(_, Failure(e)) =>
        println("  Failed: ")
        e.printStackTrace()
      case Progress(_, p) =>
        val i = (p * 25).toInt
        while (lastProg < i) {
          print("#")
          lastProg += 1
        }
    }
  }

  def feature(con: ExtrConfig)(whenDone: Boolean => Unit): Unit = {
    import Processor._
    println(s"Starting extraction... ${con.audioInput.getName}")
    var lastProg = 0
    go(FeatureExtraction)(con) {
      case Result(_, Success(_)) =>
        println("  Success.")
        whenDone(true)
      case Result(_, Failure(Aborted())) =>
        println("  Aborted")
        whenDone(false)
      case Result(_, Failure(e)) =>
        println("  Failed: ")
        e.printStackTrace()
        whenDone(false)
      case Progress(_, p) =>
        val i = (p * 25).toInt
        while (lastProg < i) {
          print("#")
          lastProg += 1
        }
    }
  }
}