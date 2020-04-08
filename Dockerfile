# Copyright (C) 2020 Bosch Software Innovations GmbH
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
# License-Filename: LICENSE

FROM frolvlad/alpine-java:jdk8-slim AS build

COPY . /usr/local/src/ort

WORKDIR /usr/local/src/ort

RUN apk add --no-cache \
        # Required for Node.js to build the reporter-web-app.
        libstdc++ \
        # Required to allow to download via a proxy with a self-signed certificate.
        ca-certificates \
        coreutils \
        openssl \
    && \
    scripts/import_proxy_certs.sh

RUN scripts/set_gradle_proxy.sh && \
    sed -i -r 's,(^distributionUrl=)(.+)-all\.zip$,\1\2-bin.zip,' gradle/wrapper/gradle-wrapper.properties && \
    ./gradlew --no-daemon --stacktrace :cli:distTar

FROM adoptopenjdk:11-jre-hotspot-bionic

COPY --from=build /usr/local/src/ort/scripts/import_proxy_certs.sh /opt/ort/bin/import_proxy_certs.sh
COPY --from=build /usr/local/src/ort/scripts/set_gradle_proxy.sh /opt/ort/bin/set_gradle_proxy.sh
COPY --from=build /usr/local/src/ort/cli/build/distributions/ort-*.tar /opt/ort.tar
RUN tar xf /opt/ort.tar -C /opt/ort --strip-components 1 && rm /opt/ort.tar

ENV \
    # Package manager versions.
    BOWER_VERSION=1.8.8 \
    BUNDLER_VERSION=1.16.1-1 \
    CARGO_VERSION=0.42.0-0ubuntu1~18.04.1 \
    COMPOSER_VERSION=1.6.3-1 \
    CONAN_VERSION=1.18.0 \
    FLUTTER_VERSION=v1.12.13+hotfix.9-stable \
    GO_DEP_VERSION=0.5.4 \
    GO_VERSION=1.13.4 \
    HASKELL_STACK_VERSION=2.1.3 \
    NPM_VERSION=6.14.2 \
    PYTHON_PIP_VERSION=9.0.1-2.3~ubuntu1.18.04.1 \
    PYTHON_PIPENV_VERSION=2018.11.26 \
    PYTHON_VIRTUALENV_VERSION=15.1.0 \
    SBT_VERSION=1.3.8 \
    YARN_VERSION=1.21.1 \
    # Scanner versions.
    SCANCODE_VERSION=3.0.2 \
    # Installation directories.
    FLUTTER_HOME=/opt/flutter \
    GOPATH=$HOME/go

ENV \
    DEBIAN_FRONTEND=noninteractive \
    PATH="$PATH:$HOME/.local/bin:$FLUTTER_HOME/bin:$FLUTTER_HOME/bin/cache/dart-sdk/bin:$GOPATH/bin:/opt/go/bin"

# Apt install commands.
RUN \
    apt-get update && \
    apt-get install -y --no-install-recommends gnupg && \
    echo 'Acquire::https::dl.bintray.com::Verify-Peer "false";' | tee -a /etc/apt/apt.conf.d/00sbt && \
    echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list && \
    curl -ksS "https://keyserver.ubuntu.com/pks/lookup?op=get&options=mr&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key adv --import - && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        # Install general tools required by this Dockerfile.
        lib32stdc++6 \
        libffi-dev \
        libgmp-dev \
        libxext6 \
        libxi6 \
        libxrender1 \
        libxtst6 \
        make \
        netbase \
        openssh-client \
        xz-utils \
        zlib1g-dev \
        # Install VCS tools (no specific versions required here).
        cvs \
        git \
        mercurial \
        subversion \
        # Install package managers (in versions known to work).
        bundler=$BUNDLER_VERSION \
        cargo=$CARGO_VERSION \
        composer=$COMPOSER_VERSION \
        npm \
        python-pip=$PYTHON_PIP_VERSION \
        python-setuptools \
        python3-pip=$PYTHON_PIP_VERSION \
        python3-setuptools \
        sbt=$SBT_VERSION \
    && \
    rm -rf /var/lib/apt/lists/*

# Custom install commands.
RUN /opt/ort/bin/import_proxy_certs.sh && \
    # Install VCS tools (no specific versions required here).
    curl -ksS https://storage.googleapis.com/git-repo-downloads/repo > /usr/local/bin/repo && \
    chmod a+x /usr/local/bin/repo && \
    # Install package managers (in versions known to work).
    npm install --global npm@$NPM_VERSION bower@$BOWER_VERSION yarn@$YARN_VERSION && \
    pip install wheel && \
    pip install conan==$CONAN_VERSION pipenv==$PYTHON_PIPENV_VERSION virtualenv==$PYTHON_VIRTUALENV_VERSION && \
    curl -ksSO https://storage.googleapis.com/flutter_infra/releases/stable/linux/flutter_linux_$FLUTTER_VERSION.tar.xz && \
    tar xf flutter_linux_$FLUTTER_VERSION.tar.xz -C $(dirname $FLUTTER_HOME) && \
    rm flutter_linux_$FLUTTER_VERSION.tar.xz && \
    chmod -R a+rw $FLUTTER_HOME && \
    flutter config --no-analytics && \
    flutter doctor && \
    # Install golang in order to have `go mod` as package manager.
    curl -ksSO https://dl.google.com/go/go$GO_VERSION.linux-amd64.tar.gz && \
    tar -C /opt -xzf go$GO_VERSION.linux-amd64.tar.gz && \
    rm go$GO_VERSION.linux-amd64.tar.gz && \
    mkdir -p $GOPATH/bin && \
    curl -ksS https://raw.githubusercontent.com/golang/dep/v$GO_DEP_VERSION/install.sh | sh && \
    curl -ksS https://raw.githubusercontent.com/commercialhaskell/stack/v$HASKELL_STACK_VERSION/etc/scripts/get-stack.sh | sh && \
    # Add scanners (in versions known to work).
    curl -ksSL https://github.com/nexB/scancode-toolkit/archive/v$SCANCODE_VERSION.tar.gz | \
        tar -zxC /usr/local && \
        # Trigger configuration for end-users.
        /usr/local/scancode-toolkit-$SCANCODE_VERSION/scancode --version && \
        chmod -R o=u /usr/local/scancode-toolkit-$SCANCODE_VERSION && \
        ln -s /usr/local/scancode-toolkit-$SCANCODE_VERSION/scancode /usr/local/bin/scancode

RUN /opt/ort/bin/ort requirements

ENTRYPOINT ["/opt/ort/bin/ort"]
