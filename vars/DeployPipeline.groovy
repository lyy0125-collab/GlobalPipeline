def call(Map config) {
    pipeline {
        agent any
        tools {
            maven 'Maven-3.9.9'
            jdk 'jdk8'
        }
        environment {
            HOST_IP      = config.HOST_IP
            USER         = config.USER
            PORT         = config.PORT
            PROJECT_NAME = config.PROJECT_NAME
            GIT_PATH     = config.GIT_PATH
            SOURCE_PATH  = config.SOURCE_PATH ?: ""
            REMOTE_DIR   = config.REMOTE_DIR ?: "/opt/app/${config.PROJECT_NAME}/"
            JAR_NAME     = config.JAR_NAME ?: "${config.PROJECT_NAME}.jar"
            GIT_BRANCH   = config.GIT_BRANCH ?: 'main'
        }
        stages {
            stage('Checkout') {
                steps {
                    git branch: GIT_BRANCH, url: GIT_PATH
                }
            }

            stage('Build') {
                steps {
                    sh "mvn clean package -f ./${SOURCE_PATH}pom.xml"
                }
            }

            stage('Deploy') {
                steps {
                    script {
                        sh """
                        ssh ${USER}@${HOST_IP} 'mkdir -p ${REMOTE_DIR}'
                        scp ${SOURCE_PATH}${PROJECT_NAME}/target/${JAR_NAME} ${USER}@${HOST_IP}:${REMOTE_DIR}
                        ssh ${USER}@${HOST_IP} 'bash ${REMOTE_DIR}startup.sh'
                        """
                    }
                }
            }

            stage('Verify') {
                steps {
                    script {
                        def url = "http://${HOST_IP}:${PORT}/actuator/health"
                        def maxTime = 180
                        def interval = 5
                        def elapsed = 0
                        def success = false
                        def lastResponse = ""

                        echo "等待 30 秒后开始检查服务健康..."
                        sleep 30

                        while (elapsed < maxTime) {
                            def code = sh(
                                script: "curl -s -w '%{http_code}' -o /tmp/resp.txt ${url} || true",
                                returnStdout: true
                            ).trim()

                            lastResponse = sh(script: "cat /tmp/resp.txt || true", returnStdout: true).trim()

                            if (code == "200") {
                                echo "✅ 服务 ${PROJECT_NAME} 健康检查成功 (HTTP 200)"
                                success = true
                                break
                            } else {
                                echo "❌ 服务响应异常，状态码: ${code}"
                            }

                            sleep interval
                            elapsed += interval
                        }

                        if (!success) {
                            echo "------ 服务最后一次返回内容 ------"
                            echo lastResponse
                            echo "--------------------------------"
                            error "服务 ${PROJECT_NAME} 启动失败"
                        }
                    }
                }
            }
        }
    }
}
