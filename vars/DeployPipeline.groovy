def call(Map config) {
    pipeline {
        agent any

        stages {
            stage('Init Tools & Envs') {
                steps {
                    script {
                        // ========== 动态工具选择 ==========
                        def mavenVersion = config.MAVEN ?: 'Maven-3.9.9'
                        def jdkVersion   = config.JDK   ?: 'jdk8'

                        env.MAVEN_HOME = tool name: mavenVersion, type: 'maven'
                        env.JAVA_HOME  = tool name: jdkVersion, type: 'jdk'
                        env.PATH = "${env.JAVA_HOME}/bin:${env.MAVEN_HOME}/bin:${env.PATH}"

                        echo "🔧 Using Maven: ${env.MAVEN_HOME}"
                        echo "🔧 Using JDK:   ${env.JAVA_HOME}"

                        // ========== 环境变量 ==========
                        env.JAVA_VERSION = config.JDK
                        env.HOST_IP      = config.HOST_IP
                        env.USER         = config.USER
                        env.PORT         = config.PORT
                        env.PROJECT_NAME = config.PROJECT_NAME
                        env.GIT_PATH     = config.GIT_PATH
                        env.SOURCE_PATH  = config.SOURCE_PATH ?: ""
                        env.REMOTE_DIR   = config.REMOTE_DIR ?: "/opt/app/${config.PROJECT_NAME}/"
                        env.JAR_NAME     = config.JAR_NAME ?: "${config.PROJECT_NAME}.jar"
                        env.GIT_BRANCH   = config.GIT_BRANCH ?: "main"
                    }
                }
            }

            stage('Checkout') {
                steps {
                    git branch: env.GIT_BRANCH, url: env.GIT_PATH
                }
            }

            stage('Build') {
                steps {
                    sh "mvn clean package -f ./${env.SOURCE_PATH}pom.xml"
                }
            }

            stage('Deploy') {
                steps {
                    def localJarPath = ""
                    if (env.SOURCE_PATH == "~") {
                        localJarPath = "target/${env.JAR_NAME}"
                    } else {
                        localJarPath = "${env.SOURCE_PATH}${env.PROJECT_NAME}/target/${env.JAR_NAME}"
                    }
                    
                    sh """
                        ssh ${env.USER}@${env.HOST_IP} 'mkdir -p ${env.REMOTE_DIR}'
                        scp ${localJarPath} ${env.USER}@${env.HOST_IP}:${env.REMOTE_DIR}
                        ssh ${env.USER}@${env.HOST_IP} 'bash /opt/app/startup.sh ${env.PROJECT_NAME} ${env.JAVA_VERSION}'
                    """
                }
            }

            stage('Verify') {
                steps {
                    script {
                        def url = "http://${env.HOST_IP}:${env.PORT}/actuator/health"
                        def interval = 5
                        def lastResponse = ""

                        echo "等待 30 秒后开始检查服务健康..."
                        sleep 30

                        try {
                            timeout(time: 3, unit: 'MINUTES') {
                                while (true) {
                                    def code = sh(
                                        script: "curl -s -w '%{http_code}' -o /tmp/resp.txt ${url} || true",
                                        returnStdout: true
                                    ).trim()

                                    lastResponse = sh(
                                        script: "cat /tmp/resp.txt || true",
                                        returnStdout: true
                                    ).trim()

                                    if (code == "200") {
                                        echo "✅ 服务 ${env.PROJECT_NAME} 健康检查成功 (HTTP 200)"
                                        return
                                    } else {
                                        echo "❌ 服务未就绪，状态码: ${code}"
                                    }

                                    sleep interval
                                }
                            }
                        } catch (err) {
                            echo "------ 服务最后一次返回内容 ------"
                            echo lastResponse
                            echo "--------------------------------"
                            error "服务 ${env.PROJECT_NAME} 启动失败 (超过 3 分钟仍未成功)"
                        }
                    }
                }
            }
        }
    }
}
