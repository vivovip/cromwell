#!/bin/bash

set -e
set -x

sudo apt-get update -qq
sudo apt-get install -qq mysql-server-5.6 mysql-client-5.6 mysql-client-core-5.6
docker pull ubuntu:latest
mysql -u root -e "SET GLOBAL sql_mode = 'STRICT_ALL_TABLES';"
mysql -u root -e "CREATE DATABASE IF NOT EXISTS cromwell_test;"
mysql -u root -e "CREATE USER 'travis'@'localhost' IDENTIFIED BY '';"
mysql -u root -e "GRANT ALL PRIVILEGES ON cromwell_test . * TO 'travis'@'localhost';"

set +x

# END of set -x. Don't log our keys.

takipisbtagent=""

sbtout=sbt.out.txt
sbtcount=5

# Sleep one minute between statuses, but don't zombie for more than two hours
travisloops=120
travissleepsecs=60
travisexitlines=30000

function install_takipi {
    echo "typesafe.subscription=${KSHAKIR_TYPESAFE_SUBSCRIPTION}" > project/typesafe.properties

    wget -O - -o /dev/null http://get.takipi.com | sudo bash /dev/stdin -i "--sk=${KSHAKIR_TAKIPI_SECRET_KEY}"

    sudo /opt/takipi/etc/takipi-setup-machine-name "TRAVIS-${TRAVIS_JOB_NUMBER}"

    takipisbtagent="-J-agentlib:TakipiAgent"
}

function compile_sbt {
    echo "$(date) sbt compile starting"
    sbt coverage nointegration:compile > ${sbtout} 2>&1
    echo
    echo "$(date) sbt compile complete"
}

function run_sbt {
    echo "$(date) sbt starting $1"
    sbt $takipisbtagent -J-Dtakipi.name="mainspec-sbt-$1" -Dbackend.providers.Local.config.filesystems.local.localization.0=copy coverage nointegration:test coverageReport > ${sbtout} 2>&1
    echo
    echo "$(date) sbt complete $1"
}

function repeat_sbt {
    for i in $(seq 1 ${sbtcount}); do
        run_sbt "$i"
    done
}

function sbt_line_count {
    wc -l ${sbtout} | awk '{print $1}'
}

function print_sbt {
    echo
    if [ -f "${sbtout}" ]; then
        cat ${sbtout}
    else
        echo "NO ${sbtout}!"
    fi
}

function print_travis_progress {
    for i in $(seq 1 ${travisloops}); do
        sleep ${travissleepsecs}
        printf "%s…" "$(sbt_line_count)"

        # We've seen logs producing more than 20000 lines. What is this?
        if [ "$(sbt_line_count)" -ge "${travisexitlines}" ]; then
            kill $$ || true
        fi
    done &
    progresspid=$!
}

function kill_travis_progress {
    if [ -n "${progresspid+set}" ]; then
        kill ${progresspid} || true
    fi
}

function handle_exit {
    print_sbt
    kill_travis_progress
}

trap handle_exit EXIT

#install_takipi

print_travis_progress

compile_sbt

repeat_sbt

exit
