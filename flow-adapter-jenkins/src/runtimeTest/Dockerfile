FROM jenkins/jenkins:2.580.1-jdk25
RUN jenkins-plugin-cli --latest=false --plugins \
    git:5.10.1 \
    pipeline-model-definition:2.2293.v6e7193cec599 \
    timestamper:1.30
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
# Runtime networking is disabled by the host. The only Git server is loopback.
ENV JENKINS_OPTS="--httpListenAddress=127.0.0.1"
