/*
 *  FeatureStats.scala
 *  (Strugatzki)
 *
 *  Copyright (c) 2011-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.strugatzki

import java.io.File

import de.sciss.processor.{ProcessorFactory, ProcessorLike}

import scala.collection.immutable.{IndexedSeq => Vec}

object FeatureStats extends ProcessorFactory.WithDefaults {
  type Product  = Vec[(Double, Double)]
  type Config   = Vec[File]
  type Repr     = FeatureStats

  protected def defaultConfig: Vec[File] = Vector.empty

  protected def prepare(config: FeatureStats.Config): Prepared =
    new impl.FeatureStatsImpl(config)
}
trait FeatureStats extends ProcessorLike[FeatureStats.Product, FeatureStats] {
  def config: FeatureStats.Config
}