FROM jenkins/jenkins:2.580.1-jdk25
RUN jenkins-plugin-cli --latest=false --plugins \
    git:5.10.1 \
    pipeline-model-definition:2.2293.v6e7193cec599 \
    timestamper:1.30 \
    pipeline-input-step:534.v352f0a_e98918
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
# Legacy fixtures have no runtime networking. The shared immutable checkout opts into public Git egress.
# Jenkins HTTP stays on loopback and no host ports are published.
ENV JENKINS_OPTS="--httpListenAddress=127.0.0.1"
