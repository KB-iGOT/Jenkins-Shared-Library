def call(Map config = [:]) {

    stage('Detect Project Type') {
        script {
            if (fileExists("pom.xml")) {
                env.PROJECT_TYPE = "java"
            } else if (fileExists("package.json")) {
                env.PROJECT_TYPE = "node"
            } else if (fileExists("requirements.txt")) {
                env.PROJECT_TYPE = "python"
            } else {
                env.PROJECT_TYPE = "unknown"
            }

            echo "📦 Project Type: ${env.PROJECT_TYPE}"
        }
    }

    stage('SonarQube Analysis') {
        script {

            if (env.PROJECT_TYPE == "java") {
                sh "mvn clean verify sonar:sonar"
            }

            else {
                sh """
                    sonar-scanner \
                      -Dsonar.projectKey=${env.JOB_NAME.replaceAll('/', '_')} \
                      -Dsonar.sources=. \
                      -Dsonar.host.url=${env.SONAR_HOST_URL} \
                      -Dsonar.token=${env.SONAR_AUTH_TOKEN}
                """
            }
        }
    }

    stage('Quality Gate') {
        timeout(time: 10, unit: 'MINUTES') {
            def qg = waitForQualityGate()
            echo "🔎 Quality Gate: ${qg.status}"

            if (qg.status != 'OK') {
                error("❌ Quality Gate Failed")
            }
        }
    }

    stage('Extract Jira ID') {
        script {
            def commitMsg = sh(
                script: "git log -1 --pretty=%B",
                returnStdout: true
            ).trim()

            def matcher = (commitMsg =~ /(KB-\d+)/)

            if (matcher.find()) {
                env.JIRA_ID = matcher.group(1)
                echo "🎫 Jira Found: ${env.JIRA_ID}"
            } else {
                env.JIRA_ID = ""
                echo "ℹ No Jira ID Found"
            }
        }
    }
}
