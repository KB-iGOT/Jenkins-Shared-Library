def call(Map c = [:]) {

    pipeline {
        agent any

        stages {
            stage('PR Validation') {
                when { changeRequest() }
                steps {
                    echo "🔍 Running central PR pipeline"
                }
            }
        }

        post {
            success {
                script {
                    githubNotify(
                        context: 'Jenkins PR Validation',
                        status: 'SUCCESS',
                        description: 'PR checks passed'
                    )
                }
            }

            failure {
                script {
                    githubNotify(
                        context: 'Jenkins PR Validation',
                        status: 'FAILURE',
                        description: 'PR checks failed'
                    )
                }
            }

            always {
                echo "🏁 Pipeline completed"
            }
        }
    }
}
