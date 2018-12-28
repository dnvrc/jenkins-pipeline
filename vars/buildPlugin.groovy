import groovy.json.*

class Globals {
  static String appName = ''
  static String shortEnv = ''
  static String imageTag = ''
  static String envDomain = ''
  static String id = ''
  static String env = ''
  static String dockerName = ''
  static String version = ''
  static String scanLanguage = ''
  static String testCommand = ''
  static String tag = ''
  static String npmRegistry = 'https://nexus.dnvr.cloud/repository/npm-group/'
  static String pythonUpload = 'python setup.py sdist upload -r nexus'
  static String credentialsId = 'dnvr-token'
}

def alert(String color, String message, Exception e=null) {
  sh "echo '${message}'"

  GString msg = "[${env.JOB_NAME}] BUILD #${env.BUILD_NUMBER}: ${message}"

  if (e) {
    msg = "${msg}: ${e.message}"
  }

  // slackSend color: color, message: msg
}

@NonCPS
static LinkedHashMap parseJson(String jsonString) {
  def lazyMap = new JsonSlurper().parseText(jsonString)
  LinkedHashMap map = [:]
  map.putAll(lazyMap)
  return map
}

def tryCatch(String name, Map success, Map fail, Closure clo=null) {
  stage(name) {
    try {
      if (clo) {
        clo()
      }
      color = success.color ?: '#ff0000'
      text = success.text
      alert(color, "${text}: ${name}".toString())
    } catch (Exception e) {
      color = fail.color ?: '#ff0000'
      text = fail.text
      doThrow = fail.doThrow ?: false
      alert(color, "${text}: ${name}".toString(), e)

      if (doThrow) {
        throw e
      }
    }
  }
}

def call(body) {
  // evaluate the body block, and collect configuration into the object
  LinkedHashMap config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  // now build, based on the configuration provided
  node {
    Map info = [color: '#0000ff', text: 'INFO', doThrow: false]
    Map success = [color: '#00ff00', text: 'SUCCESS', doThrow: false]
    Map warn = [color: '#ffff00', text: 'WARN', doThrow: false]
    Map error = [color: '#ff0000', text: 'ERROR', doThrow: true]

    tryCatch('START Pipeline', info, error)

    tryCatch('Checkout Source from Github', success, error, {
      checkout scm
    })

    String dockerUrl = "${DOCKER_URL}".toString()
    String shortGit = sh(
            script: 'git rev-parse --short HEAD',
            returnStdout: true
    ).trim().toString()
    String gitCommitNumber = "${env.BUILD_NUMBER}-${shortGit}".toString()
    def fullJobNameParts = "${env.JOB_NAME}".toString().split('/')
    String fullJobName = fullJobNameParts[0].toString()
    String branchName = "${env.BRANCH_NAME.replaceAll(/([^a-zA-Z0-9])/, '-')}".toString()
    String workspace = "${env.WORKSPACE}".toString()

    sh 'printenv'

    switch (fullJobName) {
      case "dnvr-deploy-dev":
        Globals.imageTag = 'dev'
        Globals.shortEnv = 'dev'
        Globals.env = 'development'
        Globals.tag = gitCommitNumber
        break
      case "dnvr-deploy-prod":
        Globals.shortEnv = 'prod'
        Globals.imageTag = 'latest'
        Globals.env = 'production'
        Globals.tag = branchName
        break
      case "dnvr-pr-build":
        // Do nothing
        break
      default:
        error("Unknown Jenkins organization configuration ${fullJobName}")
        break
    }

    def image

    tryCatch('Set language specific vars', success, error, {
      if (fileExists("package.json")) {
        Globals.scanLanguage = "js"
        Globals.testCommand = "NODE_ENV=test npm test".toString()
      } else if (fileExists("setup.py")) {
        Globals.scanLanguage = "py"
        Globals.testCommand = "make docker_test"
      } else if (config.testCommand) {
        Globals.testCommand = config.testCommand
      }

      if (fileExists("package.json") || config.needsNpmCredentials) {
        String up = ''
        withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: "nodasaurus",
                          usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD']]) {
          up = "${NEXUS_USERNAME}:${NEXUS_PASSWORD}".toString()
        }

        String nexusCredentials = sh(
                script: "echo -n ${up} | base64",
                returnStdout: true
        ).trim()

        String rcFile = ".npmrc"

        sh "touch ${rcFile}"
        sh "echo '_auth=\"${nexusCredentials}\"' >> ${rcFile}"
        sh "echo always-auth=true >> ${rcFile}"
        sh "echo email=nodasaurus@dnvr.cloud >> ${rcFile}"
        sh "echo registry=${Globals.npmRegistry} >> ${rcFile}"

        sh "cat ${rcFile}"
      }

      echo "scanLanguage: ${Globals.scanLanguage}, testCommand: ${Globals.testCommand}"
    })

    tryCatch('Set App Name', success, error, {
      packageName = sh(
              script: "cat package.json | jq -r '.name'",
              returnStdout: true
      ).trim()

      echo "App Name is: ${packageName}"
      echo "Short env is: ${Globals.shortEnv}"

      if (config.name) {
        Globals.appName = config.name
      } else {
        Globals.appName = packageName
      }
    })

    tryCatch('Get Docker Image Name', success, error, {
      sh "printenv"
      Globals.dockerName = "dnvr/${Globals.appName}".toString()
    })

    tryCatch('Build Image', success, error, {
      image = docker.build(Globals.dockerName, "--build-arg ENV=${Globals.env} .")
    })

    if (config.runTests) {
      tryCatch('Test Image', success, error, {
        if (config.runTests || true) {
          sh "echo 'Running tests' ${Globals.testCommand}"
          image.inside('-u 0') {
            sh "${Globals.testCommand.toString()}"
          }
        } else {
          echo "Test running is disabled"
        }
      })
    }

    echo "Full Job Name is: ${fullJobName}"
    if (!fullJobName.startsWith('dnvr-deploy')) {
      tryCatch('END Pipeline', info, error)
      return
    }

    echo "Docker Hub: ${dockerUrl}}, ${Globals.tag}, ${Globals.imageTag}"
    tryCatch('Push Image to Docker Hub', success, error, {
      docker.withRegistry("https://${dockerUrl}") {
        if(config.signWithDCT)
        {
          echo "Signing ${Globals.appName}"
          env.DOCKER_CONTENT_TRUST = '1'
          withCredentials([
            string(credentialsId: "${Globals.appName}-dct-passphrase", variable: 'DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE'),
            string(credentialsId: "dct-root-passphrase", variable: 'DOCKER_CONTENT_TRUST_ROOT_PASSPHRASE')
          ])
          {
            env.DOCKER_CONTENT_TRUST_ROOT_PASSPHRASE = "${DOCKER_CONTENT_TRUST_ROOT_PASSPHRASE}"
            env.DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE = "${DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE}"
          }
        } else {
          echo "signWithDCT is disabled. Not signing ${Globals.appName}"
        }

        image.push(Globals.tag)

        if (Globals.imageTag) {
          image.push(Globals.imageTag)
        } else {
          image.push()
        }
      }
    })

    if (!fileExists("marathon/${Globals.env}.json")) {
      tryCatch('END Pipeline', info, error)
      return
    }

    tryCatch('Deploy to Kubernetes', success, error, {

        withCredentials([[
            $class: 'AmazonWebServicesCredentialsBinding',
            credentialsId: "aws-${Globals.env}-k8s-credentials",
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        ]]) {
            withCredentials([file(credentialsId: "kubeconfig_${Globals.env}-eks", variable: 'kubeconfig_file')]) {
              sh "unset AWS_ACCESS_KEY_ID"
              sh "unset AWS_SECRET_ACCESS_KEY"
              sh "unset AWS_SESSION_TOKEN"
              sh "AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} KUBECONFIG=\$kubeconfig_file /usr/local/bin/kubectl set image deployment/dnvr-${Globals.env}-${Globals.appName} dnvr-${Globals.env}-${Globals.appName}=${Globals.dockerName}:${Globals.tag} --all"
            }
        }


    })

    tryCatch('END Pipeline', info, error)
  }
}
