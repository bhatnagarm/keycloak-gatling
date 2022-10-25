package keycloak.login

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import io.gatling.core.Predef._
import io.gatling.http.Predef._

class StandardFlowSimulation extends Simulation {
  val concurrentUserCount = default("concurrentUsers", 5)
  val simulationDuration = default("duration", 30) // seconds
  val keycloakUrl = default("keycloakUrl", "http://localhost:10080/auth")
  val realm = default("keycloakRealm", "velocity")

  val keycloakUserCount = default("keycloakUsers", 5)
  val keycloakClientCount = default("keycloakClients", 3)
  val CODE_PATTERN = "code="
  val client_id = "lms-test-client"
  val redirect_uri = "https://jwt.io"
  val logoutUrl = "https://jwt.io/logout"
  val httpProtocol = http
    .baseUrl(keycloakUrl)
    .disableFollowRedirect


  val feeder = csv("tmp.csv").random
  /*val feeder = Iterator.continually({
    val randomUserId = ThreadLocalRandom.current().nextInt(keycloakUserCount - 1)
    val randomClientId = ThreadLocalRandom.current().nextInt(keycloakClientCount - 1)
    Map(
      "userName" -> s"user-$randomUserId",
      "clientId" -> "lms-test-client",
      "redirectUrl" -> "https://jwt.io/sso/login",
      "logoutUrl" -> s"https://jwt.io/logout"
    )
  })*/


  object Keycloak {
    val loadLoginPage = exec(http("keycloak_get_login-page")
      .get(s"/realms/$realm/protocol/openid-connect/auth")
      .queryParam("client_id", "lms-test-client")
      //.queryParam("redirect_uri", "https://jwt.io/sso/login")
      .queryParam("redirect_uri", "https://jwt.io")
      .queryParam("state", UUID.randomUUID().toString())
      .queryParam("nonce", UUID.randomUUID().toString())
      .queryParam("response_type", "code")
      .queryParam("scope", "openid")
      .queryParam("login", "true")
      .check(status.is(200))
      .check(css("#velocityForm")
        .ofType[Node]
        .transform(n => {
          n.getAttribute("action")
        }).saveAs("auth_url"))
    ).exitHereIfFailed

    val authenticate = exec(http("keycloak_post_authentication")
      .post("${auth_url}")
      .formParam("username", "${userName}")
      .formParam("password", "${password}")
      .check(status.is(302))
      .check(
        status.is(302), header("Location").saveAs("login-redirect"),
        header("Location").transform(t => {
          val codeStart = t.indexOf(CODE_PATTERN)
          if (codeStart == -1) {
            exitHereIfFailed
          }
          t.substring(codeStart + CODE_PATTERN.length, t.length())
        }).notNull.saveAs("code")
      )).exitHereIfFailed
  }

  object ClientApplication {
    val codeToToken = exec(http("client-application_post_code-to-token")
      .post(s"/realms/$realm/protocol/openid-connect/token")
      .formParam("grant_type", "authorization_code")
      .formParam("code", "${code}")
      .formParam("client_id", "lms-test-client")
      .formParam("redirect_uri", "https://jwt.io")
      .check(status.is(200))
      .check(jsonPath("$..access_token").exists)
      )
      .exec(session => (session.removeAll("code")))
      .exitHereIfFailed

    val logout = exec(http("client-application_get_logout")
      .get("/realms/" + realm + "/protocol/openid-connect/logout?redirect_uri=${logoutUrl}")
      .check(status.is(302))
      .check(header("Location").is("${logoutUrl}"))
    )
  }

  val keycloakStandardFlow = scenario("keycloak-standard-flow")
    .feed(feeder)
    .exec(Keycloak.loadLoginPage)
    .pause(5)
    .group("authentication-round-trip") {
      exec(Keycloak.authenticate)
        .pause(1)
        .exec(ClientApplication.codeToToken)
    }
    .pause(10)
    .exec(ClientApplication.logout)


  setUp(
    keycloakStandardFlow.inject(constantConcurrentUsers(concurrentUserCount) during (simulationDuration))
  ).protocols(httpProtocol)


  def default[T](option: String, defaultValue: T): T = {
    if (System.getProperty(option) == null)
      return defaultValue

    (defaultValue match {
      case t: String => System.getProperty(option)
      case t: Int => System.getProperty(option).toInt
      case t@_ => throw new IllegalArgumentException("unsupported type")
    }).asInstanceOf[T]
  }
}