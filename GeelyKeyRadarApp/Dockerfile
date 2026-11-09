FROM ghcr.io/cirruslabs/android-sdk:34

RUN apt-get update -qq && \
    apt-get install -y -qq openjdk-17-jdk-headless zip && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH
ENV BUILD_TOOLS=/opt/android-sdk-linux/build-tools/34.0.0
ENV ANDROID_JAR=/opt/android-sdk-linux/platforms/android-34/android.jar
ENV SKIP_INSTALL=1

WORKDIR /app
CMD ["bash", "build.sh"]
