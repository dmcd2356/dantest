#!/bin/bash

# assumes you are running this script from the DanTester main directory that contains folders
# for each of the sub-projects.
# - The danalyzer.jar file is assumed to be located at ${DANALYZER_REPO}/dist
#
# these options help catch errors.
# 'nounset' throws an error if a parameter being used is undefined.
# 'errexit' causes any error condition to terminate the script, so it doesn't continue running.
set -o nounset
#set -o errexit

# this just makes it easier to change users if the repos are organized the same as below.
HOME="/home/dse"

# this is the location of the danalyzer repo
DANALYZER_REPO="${HOME}/Projects/isstac/danalyzer/"

function build
{
    echo "- building ${1}"
    if [[ ${1} == "danalyzer" ]]; then
        cd "${DANALYZER_REPO}"
    else
        cd "${1}"
    fi
    ant clean &> /dev/null
    ant jar &> /dev/null
    if [[ $? -ne 0 ]]; then
        echo "ERROR: command failure"
        cd ${CURDIR}
        exit 1
    fi
    cd "${CURDIR}"
}

# save current path
CURDIR=$(pwd 2>&1)

# verify danalyzer path
if [ ! -d "${DANALYZER_REPO}" ]; then
    echo "danalyzer path invalid: ${DANALYZER_REPO}"
    exit 1
fi

build "danalyzer"
build "Danparse"

# rebuild the test programs and remove the instrumented versions
build "SimpleTest"
rm -f SimpleTest/SimpleTest-dan-ed.jar

# restore original dir and remove any temp files created
cd ${CURDIR}
exit 0

