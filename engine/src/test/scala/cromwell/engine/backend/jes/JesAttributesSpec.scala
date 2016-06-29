package cromwell.engine.backend.jes

import java.net.URL

import com.typesafe.config.ConfigFactory
import cromwell.CromwellTestkitSpec
import cromwell.backend.BackendConfigurationDescriptor
import cromwell.backend.impl.jes.{JesAttributes, JesBackendLifecycleActorFactory}
import cromwell.backend.impl.jes.io.{DiskType, JesWorkingDisk}
import cromwell.core.WorkflowOptions
import cromwell.engine.workflow.WorkflowDescriptorBuilder
import cromwell.filesystems.gcs.{ApplicationDefaultMode, RefreshTokenMode, GoogleConfiguration}
import cromwell.util.EncryptionSpec
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import org.specs2.mock.Mockito
import wdl4s.ThrowableWithErrors

class JesAttributesSpec extends FlatSpec with Matchers with Mockito with BeforeAndAfterAll with WorkflowDescriptorBuilder {

  val testWorkflowManagerSystem = new CromwellTestkitSpec.TestWorkflowManagerSystem()
  override implicit val actorSystem = testWorkflowManagerSystem.actorSystem
  val workingDisk = JesWorkingDisk(DiskType.SSD, 200)

  override protected def afterAll() = {
    testWorkflowManagerSystem.shutdownTestActorSystem()
    super.afterAll()
  }

  val clientSecrets = RefreshTokenMode(name = "bar", clientId = "secret-id", clientSecret = "secret-secret")
  val backendConfigDescriptor = new BackendConfigurationDescriptor(CromwellTestkitSpec.JesBackendConfig, CromwellTestkitSpec.DefaultConfig)
  val jesBackend = new JesBackendLifecycleActorFactory(backendConfigDescriptor)

  override val anyString = ""
  val anyURL: URL = null

  val jesAttributes = new JesAttributes(
    project = anyString,
    genomicsAuth = ApplicationDefaultMode(name = "foo"),
    gcsFilesystemAuth = clientSecrets,
    executionBucket = anyString,
    endpointUrl = anyURL,
    maxPollingInterval = 600)

  it should "be verified when localizing with Refresh Token" in {
    EncryptionSpec.assumeAes256Cbc()

    val goodOptions = WorkflowOptions.fromMap(Map("refresh_token" -> "token")).get

    try {
      jesAttributes.assertWorkflowOptions(goodOptions)
    } catch {
      case e: IllegalArgumentException => fail("Correct options validation should not throw an exception.")
      case t: Throwable =>
        t.printStackTrace()
        fail(s"Unexpected exception: ${t.getMessage}")
    }

    val missingToken = WorkflowOptions.fromMap(Map.empty).get
    the [IllegalArgumentException] thrownBy {
      jesAttributes.assertWorkflowOptions(missingToken)
    } should have message s"Missing parameters in workflow options: refresh_token"
  }

  it should "parse correct JES config" in {
//    val configString = """
//          {
//             project = "myProject"
//             root = "gs://myBucket"
//             maximum-polling-interval = 600
//             [PREEMPTIBLE]
//             genomics {
//               // A reference to an auth defined in the `google` stanza at the top.  This auth is used to create
//               // Pipelines and manipulate auth JSONs.
//               auth = "service-account"
//               endpoint-url = "http://myEndpoint"
//             }
//
//             filesystems = {
//               gcs {
//                 // A reference to a potentially different auth for manipulating files via engine functions.
//                 auth = "service-account"
//               }
//             }
//          }""".stripMargin
//
//    val fullConfig = ConfigFactory.parseString(configString.replace("[PREEMPTIBLE]", "preemptible = 3"))
//    val googleConfig = GoogleConfiguration(ConfigFactory.load)
//
//    val fullAttributes = JesAttributes.apply(googleConfig, BackendConfigurationDescriptor(fullConfig, ConfigFactory.load()))
//    fullAttributes.endpointUrl.toString shouldBe new URL("http://myEndpoint").toString
//    fullAttributes.project shouldBe "myProject"
//    fullAttributes.executionBucket shouldBe "gs://myBucket"
//    fullAttributes.maxPollingInterval shouldBe 600
//
//    val noPreemptibleConfig = ConfigFactory.parseString(configString.replace("[PREEMPTIBLE]", ""))
//
//    val noPreemptibleAttributes = JesAttributes.apply(googleConfig, BackendConfigurationDescriptor(noPreemptibleConfig, ConfigFactory.load))
//    noPreemptibleAttributes.endpointUrl.toString shouldBe new URL("http://myEndpoint").toString
//    noPreemptibleAttributes.project shouldBe "myProject"
//    noPreemptibleAttributes.executionBucket shouldBe "gs://myBucket"
//    noPreemptibleAttributes.maxPollingInterval shouldBe 600
//  }
//
  it should "not parse invalid config" in {
    val nakedConfig =
      ConfigFactory.parseString("""
        |{
        |   genomics {
        |     endpoint-url = "myEndpoint"
        |   }
        |}
      """.stripMargin)

    val googleConfig = GoogleConfiguration(ConfigFactory.load)

    val exception = intercept[IllegalArgumentException with ThrowableWithErrors] {
      JesAttributes.apply(googleConfig, nakedConfig)
    }
    val errorsList = exception.errors.list
    errorsList should contain ("Could not find key: project")
    errorsList should contain ("Could not find key: root")
    errorsList should contain ("no protocol: myEndpoint")
  }

}
