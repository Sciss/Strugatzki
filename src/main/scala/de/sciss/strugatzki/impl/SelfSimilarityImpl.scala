package de.sciss.strugatzki
package impl

import de.sciss.synth.io.AudioFile
import java.io.File
import de.sciss.intensitypalette.IntensityPalette
import java.awt.image.{DataBufferInt, BufferedImage}
import javax.imageio.ImageIO
import de.sciss.span.Span
import de.sciss.processor.impl.ProcessorImpl

private[strugatzki] final class SelfSimilarityImpl(val config: SelfSimilarity.Config)
  extends SelfSimilarity with ProcessorImpl[SelfSimilarity.Product, SelfSimilarity] {

  import SelfSimilarity._

  protected def body(): Product = {
      val extr          = FeatureExtraction.Config.fromXMLFile( config.metaInput )
      val stepSize      = extr.fftSize / extr.fftOverlap

      def fullToFeat( n: Long ) = ((n + (stepSize >> 1)) / stepSize).toInt

      val halfWinLen    = fullToFeat( config.corrLen )
      val winLen        = halfWinLen * 2

      val normBuf = if( config.normalize ) {
         val afNorm = AudioFile.openRead( new File( config.databaseFolder, Strugatzki.NormalizeName ))
         try {
            require( (afNorm.numChannels == extr.numCoeffs + 1) && afNorm.numFrames == 2L )
            val b = afNorm.buffer( 2 )
            afNorm.read( b )
            b
         } finally {
            afNorm.close()
         }
      } else null // None

      val tempWeight    = config.temporalWeight
      val eInBuf        = Array.ofDim[ Float ]( extr.numCoeffs + 1, winLen )

      val afExtr = AudioFile.openRead( extr.featureOutput )
      try {
        val afStart = config.span match {
          case Span.HasStart(s) => math.max(0, fullToFeat(s))
          case _ => 0
        }
        val afStop = config.span match {
          case Span.HasStop(s) => math.min(afExtr.numFrames.toInt, fullToFeat(s))
          case _ => afExtr.numFrames.toInt
        }
         val afLen = afStop - afStart

         val numCorrs = {
           val n = math.max(0L, afLen - winLen + 1)
           // require( n <= 0xB504, "32-bit overflow" )
           require(n <= 0x7FFFFFFF, "32-bit overflow")
           n.toInt
         }
         val (decim, imgExt)  = {
            val d = config.decimation
            require( d >= 1, "Illegal decimation setting of " + d )
            val i = numCorrs / d
            if( i <= 0xB504 ) (d, i) else {
               val d1 = (numCorrs + 0xB503) / 0xB504
               println( "Warning: Decimation is too small to produce a reasonable image size. Automatically adjusting to " + d1 )
               (d1, numCorrs / d1)
            }
         }
//         require( imgExt < 0xB504, "Image size too large. Try a decimation of " + ((numCorrs + 0xB503) / 0xB504) )
         val numPix     = imgExt.toLong * imgExt.toLong
         val imgExtM1   = imgExt - 1

         if( verbose ) println( "Image extent is " + imgExt + " (yielding a matrix of " + numPix + " pixels)" )

         val colorFun: Float => Int = (config.colors, config.colorInv) match {
            case (GrayScale, false) => (sim: Float) => {
               val i = math.max( 0, math.min( 255, (sim * 255 + 0.5).toInt ))
               (i << 16) | (i << 8) | i
            }

            case (GrayScale, true) => (sim: Float) => {
               val i = math.max( 0, math.min( 255, ((1f - sim) * 255 + 0.5).toInt ))
               (i << 16) | (i << 8) | i
            }

            case (PsychoOptical, false) => IntensityPalette.apply _
            case (PsychoOptical, true)  => (sim: Float) => IntensityPalette.apply( 1f - sim )
         }
         require( config.colorWarp > 0, "Illegal color warp setting. Must be > 0, but is " + config.colorWarp )
         require( config.colorCeil > 0, "Illegal color ceil setting. Must be > 0, but is " + config.colorCeil )
         val colorWarp  = config.colorWarp
         val colorScale = 1.0f / config.colorCeil

         val img     = new BufferedImage( imgExt, imgExt, BufferedImage.TYPE_INT_RGB )
         val imgData = img.getRaster.getDataBuffer.asInstanceOf[ DataBufferInt ].getData()
         val g       = img.createGraphics()
         try {

            if( afStart > 0 ) afExtr.seek( afStart )
            var leftOff    = 0
            var progBlock  = 0
            val stop       = numCorrs / decim * decim

            while( leftOff < stop ) {
               checkAborted()

               // XXX inefficient -- should just read the extra frame in successive iterations
               afExtr.seek( leftOff + afStart )
               afExtr.read( eInBuf, 0, halfWinLen )
               MathUtil.normalize( normBuf, eInBuf, 0, halfWinLen )

               var rightOff   = leftOff
               while( rightOff < stop ) {

                  // XXX inefficient -- should just read the extra frame in successive iterations
                  afExtr.seek( rightOff + afStart )
                  afExtr.read( eInBuf, halfWinLen, halfWinLen )
                  MathUtil.normalize( normBuf, eInBuf, halfWinLen, halfWinLen )

                  val temporal = if( tempWeight > 0f ) {
                     MathUtil.correlateHalf( 1, halfWinLen, eInBuf, 0, 0 )
                  } else 0f
                  val spectral = if( tempWeight < 1f ) {
                     MathUtil.correlateHalf( extr.numCoeffs, halfWinLen, eInBuf, 0, 1 )
                  } else 0f
                  val sim  = temporal * tempWeight + spectral * (1f - tempWeight)
                  val colr = colorFun( math.pow( math.max( 0f, sim ), colorWarp ).toFloat * colorScale )

                  val off1 = (imgExtM1 - rightOff/decim) * imgExt + (leftOff/decim)
                  val off2 = (imgExtM1 - leftOff/decim)  * imgExt + (rightOff/decim)
                  imgData( off1 ) = colr
                  imgData( off2 ) = colr

                  progBlock = (progBlock + 1) % 128
                  if( progBlock == 0 ) {
                     val off3 = (leftOff/decim) * imgExt + (rightOff/decim)
                     progress( off3.toFloat / numPix )
                  }
                  rightOff += decim
               }
               leftOff += decim
            }

            ImageIO.write( img, "png", config.imageOutput )
            progress( 1f )

         } finally {
            g.dispose()
            img.flush()
         }
      } finally {
         afExtr.close()
      }
   }
}
