#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

set -eo pipefail

ROOT=`dirname "$0"`
ROOT=`cd "$ROOT"; pwd`

export STARROCKS_HOME=${ROOT}

. ${STARROCKS_HOME}/env.sh

# Check args
usage() {
  echo "
Usage: $0 <options>
  Optional options:
     --test [TEST_NAME]         run specific test
     --filter [TEST_NAME]       skip run specific test,
                                multiple tests separated by commas and enclosed in quotation marks
     --dry-run                  dry-run unit tests
     --coverage                 run coverage statistic tasks
     --dumpcase [PATH]          run dump case and save to path
     -j [N]                     build parallel, default is ${FE_UT_PARALLEL:-4}

  Eg.
    $0                                            run all unit tests
    $0 --test com.starrocks.utframe.Demo          run demo test
    $0 --filter com.starrocks.utframe.Demo#Test0  skip run demo test0
    $0 --dry-run                                  dry-run unit tests
    $0 --coverage                                 run coverage statistic tasks
    $0 --dumpcase /home/disk1/                    run dump case and save to path
    $0 -j16 --test com.starrocks.utframe.Demo     run demo test with 16 parallel threads
  "
  exit 1
}

# -l run only used for compatibility
OPTS=$(getopt \
  -n $0 \
  -o 'j:' \
  -l 'test:' \
  -l 'filter:' \
  -l 'dry-run' \
  -l 'coverage' \
  -l 'dumpcase' \
  -l 'help' \
  -l 'run' \
  -- "$@")

if [ $? != 0 ] ; then
    usage
fi

eval set -- "$OPTS"

HELP=0
DRY_RUN=0
RUN_SPECIFIED_TEST=0
TEST_NAME=*
FILTER_TEST=""
COVERAGE=0
DUMPCASE=0
PARALLEL=${FE_UT_PARALLEL:-4}
while true; do
    case "$1" in
        --coverage) COVERAGE=1 ; shift ;;
        --test) RUN_SPECIFIED_TEST=1; TEST_NAME=$2; shift 2;;
        --filter) FILTER_TEST=$2; shift 2;;
        --run) shift ;; # only used for compatibility
        --dumpcase) DUMPCASE=1; shift ;;
        --dry-run) DRY_RUN=1 ; shift ;;
        --help) HELP=1 ; shift ;;
        -j) PARALLEL=$2; shift 2 ;;
        --) shift ;  break ;;
        *) echo "Internal error" ; exit 1 ;;
    esac
done

if [ ${HELP} -eq 1 ]; then
    usage
    exit 0
fi

echo "*********************************"
echo "  Starting to Run FE Unit Tests  "
echo "*********************************"

cd ${STARROCKS_HOME}/fe/
mkdir -p build/compile

# Set FE_UT_PARALLEL if -j parameter is provided
export FE_UT_PARALLEL=$PARALLEL
echo "Unit test parallel is: $FE_UT_PARALLEL"

if [ -d "./mocked" ]; then
    rm -r ./mocked
fi

if [ -d "./ut_ports" ]; then
    rm -r ./ut_ports
fi

mkdir ut_ports

if [[ ${DUMPCASE} -ne 1 ]]; then
    DUMP_FILTER_TEST="com.starrocks.sql.dump.QueryDumpRegressionTest,com.starrocks.sql.dump.QueryDumpCaseRewriter"

    if [[ $FILTER_TEST != "" ]];then
        FILTER_TEST="${FILTER_TEST},${DUMP_FILTER_TEST}"
    else
        FILTER_TEST="${DUMP_FILTER_TEST}"
    fi

    FILTER_TEST=`echo $FILTER_TEST | sed -E 's/([^,]+)/!\1/g'`
    TEST_NAME="$TEST_NAME,$FILTER_TEST"
fi

if [ ${COVERAGE} -eq 1 ]; then
    echo "Run coverage statistic tasks"
    ant cover-test
elif [ ${DUMPCASE} -eq 1 ]; then
    ${MVN_CMD} test -DfailIfNoTests=false -DtrimStackTrace=false -D test=com.starrocks.sql.dump.QueryDumpRegressionTest -D dumpJsonConfig=$1
else
    if [ $DRY_RUN -eq 0 ]; then
        if [ ${RUN_SPECIFIED_TEST} -eq 1 ]; then
            echo "Run test: $TEST_NAME"
        else
            echo "Run All Frontend Unittests"
        fi

        # set trimStackTrace to false to show full stack when debugging specified class or case
        ${MVN_CMD} test -DfailIfNoTests=false -DtrimStackTrace=false -D test="$TEST_NAME" -T $PARALLEL
    fi
fi
