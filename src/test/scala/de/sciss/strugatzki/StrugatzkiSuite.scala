package de.sciss.strugatzki

import de.sciss.file._
import de.sciss.span.Span
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec

class StrugatzkiSuite extends AnyFeatureSpec with GivenWhenThen {
  Feature("The xml representations should be correct") {
    info("Settings and result objects are created,")
    info("and transformed forward and backward to XML")

    Scenario("Creating arbitrary configurations") {
      Given("A FeatureExtraction.Settings configuration")
      val fe            = FeatureExtraction.Config()
      fe.audioInput     = file("testing.aif").absolute
      fe.featureOutput  = file("relative.aif")
      fe.metaOutput     = None
      fe.numCoeffs     += 1
      fe.fftSize       += 1
      fe.fftOverlap    += 1
      val fe1           = fe.build
      fe.metaOutput     = Some(fe.audioInput.parent)
      val fe2           = fe.build
      When("it is transformed to XML and back to the settings object")
      val fe1t = FeatureExtraction.Config.fromXML(fe1.toXML)
      val fe2t = FeatureExtraction.Config.fromXML(fe2.toXML)
      Then("the result should equal the original settings object")
      assert(fe1 == fe1t)
      assert(fe2 == fe2t)

      Given("A FeatureCorrelation.Settings configuration")
      val fc = FeatureCorrelation.Config()
      fc.databaseFolder = new File("db").getCanonicalFile
      fc.metaInput = new File("rarara.xml")
      fc.punchIn = {
        val old = fc.punchIn
        old.copy(span = Span(old.span.start + 1, old.span.stop + 2), temporalWeight = old.temporalWeight + 0.11f)
      }
      fc.punchOut = Some(FeatureCorrelation.Punch(Span(555, 666), 0.1234f))
      fc.minPunch += 1
      fc.maxPunch += 2
      fc.normalize = !fc.normalize
      fc.maxBoost += 1
      fc.numMatches += 1
      fc.numPerFile += 1
      fc.minSpacing += 1
      val fc1 = fc.build
      fc.punchOut = None
      fc.normalize = !fc.normalize
      val fc2 = fc.build
      When("it is transformed to XML and back to the settings object")
      //         println( fc1.toXML )
      val fc1t = FeatureCorrelation.Config.fromXML(fc1.toXML)
      val fc2t = FeatureCorrelation.Config.fromXML(fc2.toXML)
      //         println( fc1t.toXML )
      Then("the result should equal the original settings object")
      assert(fc1 == fc1t)
      assert(fc2 == fc2t)

      Given("A FeatureCorrelation.Match result")
      val fm1 = FeatureCorrelation.Match(0.23f, new File("gaga.aif"), Span(33, 44), -6f, -7f)
      val fm2 = FeatureCorrelation.Match(0.46f, new File("rara.wav").getAbsoluteFile, Span(666, 777), 8f, 9f)
      When("it is transformed to XML and back to the match object")
      val fm1t = FeatureCorrelation.Match.fromXML(fm1.toXML)
      val fm2t = FeatureCorrelation.Match.fromXML(fm2.toXML)
      Then("the result should equal the original match object")
      assert(fm1 == fm1t)
      assert(fm2 == fm2t)

      Given("A FeatureSegmentation.Settings configuration")
      val fs = FeatureSegmentation.Config()
      fs.databaseFolder = new File("db").getCanonicalFile
      fs.metaInput = new File("rarara.xml")
      fs.span = Span(1, 2)
      fs.corrLen += 1
      fs.temporalWeight += 0.1f
      fs.normalize = !fs.normalize
      fs.numBreaks += 1
      fs.minSpacing += 1
      val fs1 = fs.build
      fs.span = Span.all
      fs.normalize = !fs.normalize
      val fs2 = fs.build
      When("it is transformed to XML and back to the settings object")
      //         println( fs1.toXML )
      val fs1t = FeatureSegmentation.Config.fromXML(fs1.toXML)
      val fs2t = FeatureSegmentation.Config.fromXML(fs2.toXML)
      //         println( fs1t.toXML )
      Then("the result should equal the original settings object")
      assert(fs1 == fs1t)
      assert(fs2 == fs2t)
    }
  }
}