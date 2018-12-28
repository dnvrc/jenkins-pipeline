import groovy.json.*

class Globals {
    static String name = ''
    static String dockerName = ''
    static String language = ''
    static String buildCommand = ''
    static String packageUpload = ''
    static String testCommand = ''
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

        Globals.name = "${config.name}".toString()

        sh 'printenv'

        def image

        tryCatch('START Package Build', info, error)

        tryCatch('Checkout Source from Github', success, error, {
            checkout scm
        })

        String shortGit = sh (
                script: 'git rev-parse --short HEAD',
                returnStdout: true
        ).trim().toString()
        String gitCommitNumber = "${env.BUILD_NUMBER}-${shortGit}".toString()
        String workspace = "${env.WORKSPACE}".toString()

        tryCatch('Set language specific vars', success, error, {
            if (fileExists("package.json")) {
                Globals.language = "js".toString()
                Globals.buildCommand = "npm install".toString()
                Globals.testCommand = "NODE_ENV=test npm test".toString()
                Globals.packageUpload = "npm publish".toString()
            } else if (fileExists("setup.py")) {
                Globals.language = "py".toString()
                Globals.buildCommand = "make docker_install".toString()
                Globals.testCommand = "make docker_test".toString()
                Globals.packageUpload = "python setup.py sdist upload -r nexus".toString()
            } else {
                error("Unknown package configuration file")
            }

            echo "language: ${Globals.language}, testCommand: ${Globals.testCommand}"
        })

        tryCatch('Get Docker Image Name', success, error, {
            Globals.dockerName = "${Globals.name}-${gitCommitNumber}".toString()
        })

        tryCatch('Build Image', success, error, {

            image = docker.build(Globals.dockerName)
            image.inside('-u 0') {
                sh "${Globals.buildCommand.toString()}"
            }
        })

        if (config.runTests) {
            tryCatch('Test Image', success, error, {
                sh "echo 'Running tests' ${Globals.testCommand}"
                image.inside('-u 0') {
                    sh "${Globals.testCommand.toString()}"
                }
            })
        } else {
            echo "Test running is disabled"
        }

        tryCatch('Upload Package', success, error, {
            sh "echo 'Uploading package' ${Globals.packageUpload}"
            image.inside('-u 0') {
                sh "${Globals.packageUpload.toString()}"
            }
        })

        tryCatch('END Package Upload', info, error)

    }
}
