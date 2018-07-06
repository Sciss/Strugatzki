/*
 *  MathUtil.scala
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

package de.sciss.strugatzki.impl

object MathUtil {
  /** Calculates the mean and standard deviation of a given matrix
    *
    * @param   mat         the matrix to analyse
    * @param   frameOff    0 if the whole matrix is to be considered,
    *                      otherwise column offset
    * @param   frameLen    the number of columns to analyse
    * @param   chanOff     0 if the whole matrix is to be considered,
    *                      otherwise row offset
    * @param   chanLen     the number of rows to analyse
    *
    * @return              the tuple (mean, stddev)
    */
   def stat(mat: Array[Array[Float]], frameOff: Int, frameLen: Int,
            chanOff: Int, chanLen: Int): (Double, Double) = {
    val chanStop  = chanOff  + chanLen
    val frameStop = frameOff + frameLen

    var sum = 0.0
    var ch  = chanOff
    while (ch < chanStop) {
      val cb  = mat(ch)
      var i   = frameOff
      while (i < frameStop) {
        sum += cb(i)
        i += 1
      }
      ch += 1
    }
    val matSize = frameLen * chanLen
    val mean    = sum / matSize

    sum = 0.0
    ch  = chanOff
    while (ch < chanStop) {
      val cb  = mat(ch)
      var i   = frameOff
      while (i < frameStop) {
        val d = cb(i) - mean
        sum += d * d
        i   += 1
      }
      ch += 1
    }
    val stddev = math.sqrt(sum / matSize)
    (mean, stddev)
  }

  /** Perform cross correlation between two horizontal halves of a matrix `a`.
    *
    * For efficiency reasons, `a` may be updated in a rotational manner, thus `frameOff + halfWinSize`
    * may exceed the number of frames in `a` (the algorithm automatically takes the modulus).
    *
    * @param   chanOff     the channel or row offset in the matrix
    * @param   numChannels the number of channels or rows to process in the matrix
    * @param   halfWinSize half of the number of frames or columns in the matrix. The algorithm
    *          performs a cross correlation between the first half frame beginning at offset
    *          `frameOff` and the second half beginning at `frameOff + halfWinSize` (possibly
    *          wrapped around the array boundaries)
    * @param   a           the matrix to analyse
    *
    * @return  the cross correlation coefficient (sum of cell multiplies, divided by product of
    *          variance and matrix size)
    */
   def correlateHalf(numChannels: Int, halfWinSize: Int, a: Array[Array[Float]], frameOff: Int, chanOff: Int): Float = {
    val numFrames       = halfWinSize << 1
    val (mean, stdDev)  = stat(a, 0, numFrames, chanOff, numChannels)
    val add             = -mean
    val matSize         = numChannels * halfWinSize

    var sum = 0.0
    var ch  = 0
    while (ch < numChannels) {
      val ca  = a(ch + chanOff)
      var i   = frameOff
      val j   = frameOff + halfWinSize
      while (i < j) {
        sum += (ca(i % numFrames) + add) * (ca((i + halfWinSize) % numFrames) + add)
        i += 1
      }
      ch += 1
    }
    (sum / (stdDev * stdDev * matSize)).toFloat // ensures correlate( a, a ) == 1.0
  }

  /** Calculates the mean of a vector
    *
    * @param   b  the vector
    * @param   off   the offset into the vector
    * @param   len   the number of samples to take into account
    *
    * @return  the average of the `len` samples starting at offset `off` in vector `b`.
    */
  def avg(b: Array[Float], off: Int, len: Int): Float = {
    var sum   = 0.0
    var i     = off
    val stop  = off + len
    while (i < stop) {
      sum += b(i)
      i += 1
    }
    (sum / len).toFloat
  }

  /** Normalizes a matrix with respect to a normalization vector. For each row in the matrix, given
    * the rows minimum and maximum value through the normalization vector, every cell is offset
    * by `-minimum` and then divided by `maximum - minimum`.
    *
    * @param   normBuf  provides normalization vector. The outer array must be of size `b.length`.
    *                   each element of that outer array holds an array of size 2, specifying
    *                   minimum and maximum value for the index. The `normBuf` argument may be
    *                   `null`, in which case this method simply returns without any adjustments.
    * @param   b        the matrix which is normalized in-place (the values are scaled and overwritten).
    * @param   bOff     a frame of column offset in the matrix `b`.
    * @param   bLen     the number of frames or columns to process in the matrix `b`.
    */
  def normalize(normBuf: Array[Array[Float]], b: Array[Array[Float]], bOff: Int, bLen: Int): Unit = {
    if (normBuf == null) return
    var ch    = 0
    val numCh = b.length
    while (ch < numCh) {
      val cb  = b(ch)
      val cn  = normBuf(ch)
      val min = cn(0)
      val max = cn(1)
      val d   = max - min
      var i   = bOff
      val iStop = bOff + bLen
      while (i < iStop) {
        val f = cb(i)
        // XXX should values be clipped to [0...1] or not?
        cb(i) = (f - min) / d
        i += 1
      }
      ch += 1
    }
  }

  /** Perform cross correlation between two matrices a and b. Because either `a` or `b` may be static,
    * the method expects the means and standard deviations of matrices to be passed in.
    *
    * While `a` is considered a full matrix with at least `numChannels` rows and at least
    * `numFrames` columns, offset can be given for the `b` matrix.
    *
    * For efficiency reasons, `b` may be updated in a rotational manner, thus `bFrame + frameLen`
    * may exceed the number of frames in `b`. The algorithm automatically takes the modulus
    * `bFrame + frameLen % b.numFrames` as offset when doing the calculations.
    *
    * @param   a           the first matrix
    * @param   b           the second matrix
    * @param   aMean       the mean of the samples in `a`
    * @param   bMean       the mean of the samples in `b`
    * @param   aStdDev     the standard deviation of the samples in `a`
    * @param   bStdDev     the standard deviation of the samples in `b`
    * @param   numChannels the number of rows in `a`, also the number of rows considered in the correlation
    * @param   numFrames   the number of columns in `a`, also the number of columns considered in the correlation
    * @param   bChanOff    channel or row offset in `b`
    * @param   bFrameOff   frame or column offset in `b`
    *
    * @return  the cross correlation coefficient
    */
  def correlate(a: Array[Array[Float]], aMean: Double, aStdDev: Double, numFrames: Int, numChannels: Int,
                b: Array[Array[Float]], bMean: Double, bStdDev: Double, bFrameOff: Int, bChanOff: Int): Float = {
    val aAdd      = -aMean
    val bAdd      = -bMean
    val aMatSize  = numChannels * numFrames

    var sum = 0.0
    var ch = 0
    while (ch < numChannels) {
      val ca = a(ch) // a.mat( ch )
      val cb = b(ch + bChanOff)
      var i = 0
      while (i < numFrames) {
        sum += (ca(i) + aAdd) * (cb((i + bFrameOff) % cb.length) + bAdd)
        i += 1
      }
      ch += 1
    }
    (sum / (aStdDev * bStdDev * aMatSize)).toFloat // ensures correlate( a, a ) == 1.0
  }
}