package org.jetbrains.plugins.scala.failed.annotator

import org.jetbrains.plugins.scala.PerfCycleTests
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestAdapter
import org.junit.experimental.categories.Category

/**
  * Created by kate on 3/28/16.
  */

//check in ScalaAnnotator with ModifierChecker.checkModifiers
@Category(Array(classOf[PerfCycleTests]))
class OverrideModifierForTypeDefenitionsTest extends ScalaLightCodeInsightFixtureTestAdapter{
  def testSCL9700(): Unit = {
    checkTextHasNoErrors(
      """
        |trait T {
        |  val t: Any
        |}
        |
        |object U extends T {
        |  override object t
        |}
      """.stripMargin
    )
  }
}
