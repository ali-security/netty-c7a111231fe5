# Sealed build environment setup, sourced by .travis.yml (before_install) and by
# .github/workflows/seal-build.yml. Do not use `set -e`: this file is sourced.
#
# 1. Azul Zulu 7: the published jar was built with JDK 1.7.0_25 (Build-Jdk) and
#    the enforcer rule requires JDK >= 1.7; neither Travis nor setup-java still
#    provision a JDK 7. The JDK already on PATH (openjdk8 / temurin 8) donates its
#    newer CA bundle so Zulu 7 can reach Maven Central.
# 2. Maven 3.8.8 (runs on Java 7, speaks TLS 1.2 to Maven Central).
# 3. java.security override (.travis/seal-java.security): the SSL echo tests use
#    the securechat example keystore, whose certificate has a 512-bit RSA key that
#    JDK 7u352's default certpath/TLS restrictions reject. Surefire runs with
#    forkMode=never, so MAVEN_OPTS reaches the tests.
SEAL_JDK=${SEAL_JDK:-zulu7.56.0.11-ca-jdk7.0.352-linux_x64}
SEAL_MAVEN=${SEAL_MAVEN:-3.8.8}
SEAL_ROOT=$(pwd)

if [ -n "$JAVA_HOME" ]; then
  SEAL_DONOR="$JAVA_HOME"
else
  SEAL_DONOR=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
fi
SEAL_CACERTS=""
for c in "$SEAL_DONOR/jre/lib/security/cacerts" "$SEAL_DONOR/lib/security/cacerts"; do
  [ -f "$c" ] && SEAL_CACERTS="$c" && break
done
[ -n "$SEAL_CACERTS" ] || { echo "no donor cacerts under $SEAL_DONOR"; return 1; }

mkdir -p /tmp/seal-jdk7 || return 1
curl -fsSL --retry 5 --retry-delay 10 -o /tmp/seal-jdk7.tar.gz \
  "https://cdn.azul.com/zulu/bin/$SEAL_JDK.tar.gz" || return 1
tar -xzf /tmp/seal-jdk7.tar.gz -C /tmp/seal-jdk7 --strip-components=1 || return 1
cp "$SEAL_CACERTS" /tmp/seal-jdk7/jre/lib/security/cacerts || return 1
export JAVA_HOME=/tmp/seal-jdk7
export PATH="$JAVA_HOME/bin:$PATH"

curl -fsSL --retry 5 --retry-delay 10 -o /tmp/seal-maven.tar.gz \
  "https://archive.apache.org/dist/maven/maven-3/$SEAL_MAVEN/binaries/apache-maven-$SEAL_MAVEN-bin.tar.gz" || return 1
tar -xzf /tmp/seal-maven.tar.gz -C /tmp || return 1
export M2_HOME="/tmp/apache-maven-$SEAL_MAVEN"
export PATH="$M2_HOME/bin:$PATH"

export MAVEN_OPTS="-Dhttps.protocols=TLSv1.2 -Djava.security.properties=$SEAL_ROOT/.travis/seal-java.security"

java -version
mvn -version
