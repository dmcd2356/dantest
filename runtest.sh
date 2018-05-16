#!/bin/bash

# assumes you are running this script from the dantester main directory that contains folders
# for each of the sub-projects.

if [ -z ${JAVA_HOME} ]; then
    JAVA_HOME="/usr/lib/jvm/java-8-openjdk-amd64"
fi

# these options help catch errors.
# 'nounset' throws an error if a parameter being used is undefined.
# 'errexit' causes any error condition to terminate the script, so it doesn't continue running.
set -o nounset
#set -o errexit

# this just makes it easier to change users if the repos are organized the same as below.
HOME="/home/dse"

# this is the location of the danalyzer repo
DANALYZER_REPO="${HOME}/Projects/isstac/danalyzer/"
DANHELPER_REPO="${HOME}/Projects/isstac/danhelper/"

# adds specified jar file to $CLASSPATH
#
# inputs: $1 = jar file (plus full path) to add
#
function add_to_classpath
{
    if [[ -z $1 ]]; then
        return
    fi

    if [[ -z ${CLASSPATH} ]]; then
        CLASSPATH="$1"
    else
        CLASSPATH="${CLASSPATH}:$1"
    fi
}

# adds all of the jar files in the specified path to $CLASSPATH except the $PROJJAR, since the
# danalyzed version is added in its place.
#
# inputs: $1 = path of lib files to add
#
function add_dir_to_classpath
{
    if [[ "$1" == "" ]]; then
        jarpath=`pwd`
    elif [[ ! -d "$1" ]]; then
        return
    else
        jarpath="$1"
    fi
    while read -r jarfile; do
        select=(${jarfile})
        select="${select##*/}"      # remove leading '/' from filename
        # skip anything that is not a jar file
        if [[ "${select}" != *".jar" ]]; then
            continue
        fi
        # ignore the project jar file, since we have already included the danalyzed version of it
        if [[ "${select}" != "${TESTNAME}.jar" &&
              "${select}" != "${TESTNAME}-dan-ed.jar" ]]; then
            if [[ "$1" != "" ]]; then
                jarfile="$1/${jarfile}"
            fi
            add_to_classpath ${jarfile}
            #echo "add_dir_to_classpath: ${jarfile}"
        fi
    done < <(ls ${jarpath})
}

function exit_cleanup
{
    echo "exiting..."
}

function helpmsg
{
    echo "runtest.sh [options] <program_name> <args>"
    echo ""
    echo "Where: <program_name> = name of the program to run (e.g. SimpleTest)"
    echo "       <args> = the argument list to pass to the specified test program"
    echo "options:"
    echo "       -t  = display state change info for debugging test"
    echo "       -T  = display state and parsing info for debugging test"
    echo "       -p  = force rebuild of danparse prior to running"
    echo "       -f  = force rebuild of program and re-instrument prior to running"
    echo ""
    echo "use -p if danparse source has changed"
    echo "use -f if test program source has changed or danalyzer has been rebuilt"
    echo ""
}

#=======================================================================
# start here:
if [[ $# -eq 0 ]]; then
    helpmsg
    exit 0
fi

# read options
TESTMODE=""
INSTRUMENT=0
UPDATEPARSE=0
ARGCOUNT=0
COMMAND=()
while [[ $# -gt 0 ]]; do
    key="$1"
    case ${key} in
        -f)
            INSTRUMENT=1
            shift
            ;;
        -p)
            UPDATEPARSE=1
            shift
            ;;
        -t)
            if [[ "${TESTMODE}" == "" ]]; then
                TESTMODE="-t"
            fi
            shift
            ;;
        -T)
            TESTMODE="-T"
            shift
            ;;
        *)
            COMMAND+=("$1")
            shift
            ARGCOUNT=`expr ${ARGCOUNT} + 1`
            ;;
    esac
done


# the 1st remaining word is the test name and the remainder terms are the arguments to pass to it
TESTNAME="${COMMAND[@]:0:1}"
ARGLIST="${COMMAND[@]:1}"

# verify arguments
#if [[ ${TESTNAME} == "" ]]; then
#    echo "ERROR: missing name of test to run"
#    echo ""
#    helpmsg
#    exit 1
#fi

# get the main class of the specified test program
case ${TESTNAME} in
    SimpleTest)     MAINCLASS="SimpleTest" ;;
    *)  echo "ERROR: Invalid test selection: ${TESTNAME}"
        helpmsg
        exit 1 ;;
esac

# save current path
CURDIR=$(pwd 2>&1)

# specify the names of the files for danparse to produce
CFGFILE="danfig"
RAWFILE="${CURDIR}/testraw.txt"
OUTFILE="${CURDIR}/testresult.txt"

# verify danalyzer path
if [ ! -d "${DANALYZER_REPO}" ]; then
    echo "danalyzer path invalid: ${DANALYZER_REPO}"
    exit 1
fi
if [[ ! -f "${DANALYZER_REPO}/dist/danalyzer.jar" || ${INSTRUMENT} -eq 1 ]]; then
    echo "- building danalyzer"
    cd "${DANALYZER_REPO}"
    ant jar &> /dev/null
    if [[ $? -ne 0 ]]; then
        echo "ERROR: ant command failure"
        exit_cleanup
        exit 1
    fi
    cd "${CURDIR}"

    # set flag to instrument test program, since danalyzer source may have changed
    INSTRUMENT=1
fi

# if any of the jar files are missing, build them now
FOLDER="danparse"
if [[ ! -f "${FOLDER}/dist/${FOLDER}.jar" || ${UPDATEPARSE} -eq 1 ]]; then
    echo "- building ${FOLDER}"
    cd "${FOLDER}"
    ant jar &> /dev/null
    if [[ $? -ne 0 ]]; then
        echo "ERROR: ant command failure"
        exit_cleanup
        exit 1
    fi
    cd "${CURDIR}"
fi

FOLDER="${TESTNAME}"
if [[ ! -f "${FOLDER}/dist/${FOLDER}.jar" || ${INSTRUMENT} -eq 1 ]]; then
    # build test program project
    echo "- building test program: ${FOLDER}"
    cd "${FOLDER}"
    javac ${TESTNAME}.java
    if [[ $? -ne 0 ]]; then
        echo "ERROR: javac command failure"
        exit_cleanup
        exit 1
    fi
    jar cvf ${TESTNAME}.jar *.class
    if [[ $? -ne 0 ]]; then
        echo "ERROR: jar command failure"
        exit_cleanup
        exit 1
    fi
    cd "${CURDIR}"

    # since we re-built test program, remove old danalyzed version so we will re-danalyze it
    rm -f ${FOLDER}/${FOLDER}-dan-ed.jar
fi

# make sure agent lib has been built
cd ${DANHELPER_REPO}

NOAGENT=0
AGENTLIBDIR="${DANHELPER_REPO}src/"
if [ ! -f "${DANHELPER_REPO}src/libdanhelper.so" ]; then
    if [ ! -f "${DANHELPER_REPO}libdanhelper.so" ]; then
        NOAGENT=1
    else
        AGENTLIBDIR="${DANHELPER_REPO}"
    fi
fi

# (this is run from DANHELPER_REPO)
if [[ ${NOAGENT} -ne 0 ]]; then
    echo "- building danhelper agent"
    make
    if [ -f "${DANHELPER_REPO}src/libdanhelper.so" ]; then
        mv ${DANHELPER_REPO}src/libdanhelper.so ${DANHELPER_REPO}libdanhelper.so
        AGENTLIBDIR="${DANHELPER_REPO}"
    elif [ -f "${DANHELPER_REPO}libdanhelper.so" ]; then
        echo "ERROR: danhelper agent failed to build"
        exit_cleanup
        exit 1
    fi
fi

# perform the following in the test program folder
cd "${CURDIR}/${TESTNAME}"

    # set danalyzer properties for this project to enable "TEST" and "UNINSTR" messages
    if [ -f ${CFGFILE} ]; then
        rm -f ${CFGFILE}
    fi
    echo "#! DANALYZER SYMBOLIC EXPRESSION LIST" > ${CFGFILE}
    echo "Thread:" >> ${CFGFILE}
    echo "DebugMode:      STDOUT" >> ${CFGFILE}
    echo "DebugFlags:     AGENT, CALLS" >> ${CFGFILE}
    echo "TriggerAuto:    0" >> ${CFGFILE}
    echo "TriggerAnyMeth: 0" >> ${CFGFILE}
    echo "TriggerOnCall:  0" >> ${CFGFILE}

    # setup the classpath
    CLASSPATH=""
    add_to_classpath "${TESTNAME}-dan-ed.jar"

    # add the libraries required for danalyzed files
    add_to_classpath "${DANALYZER_REPO}lib/commons-io-2.5.jar"
    add_to_classpath "${DANALYZER_REPO}lib/asm-all-5.2.jar"
    add_to_classpath "${DANALYZER_REPO}lib/com.microsoft.z3.jar"
#    add_to_classpath "${DANALYZER_REPO}lib/jgraphx.jar"

    # if not found or requested, danalyze the test program
    FOLDER="${TESTNAME}"
    if [[ ${INSTRUMENT} -eq 1 || ! -f ${FOLDER}-dan-ed.jar ]]; then
        # run danalyzer on the application jar to instrument it
        echo "- instrumenting ${FOLDER} for danalyzer"
        java -cp ${CLASSPATH} -jar ${DANALYZER_REPO}dist/danalyzer.jar ${TESTNAME}.jar
        if [[ $? -ne 0 ]]; then
            echo "ERROR: java command failure"
            echo "java -cp ${CLASSPATH} -jar ${DANALYZER_REPO}dist/danalyzer.jar ${TESTNAME}.jar"
            exit_cleanup
            exit 1
        fi
    fi

    # setup classpath
    CLASSPATH=""
    add_to_classpath "${TESTNAME}-dan-ed.jar"
    add_to_classpath "${DANALYZER_REPO}lib/commons-io-2.5.jar"
    add_to_classpath "${DANALYZER_REPO}lib/asm-all-5.2.jar"
    add_to_classpath "${DANALYZER_REPO}lib/com.microsoft.z3.jar"
    add_dir_to_classpath ""
    add_dir_to_classpath "lib"

    # run the instrumented code with the agent
    OPTIONS="-Xverify:none -Dsun.boot.library.path=$JAVA_HOME/bin:/usr/lib"
    BOOTCLASSPATH="-Xbootclasspath/a:${DANALYZER_REPO}dist/danalyzer.jar:${DANALYZER_REPO}lib/com.microsoft.z3.jar"
    AGENTPATH="-agentpath:${AGENTLIBDIR}libdanhelper.so"

    # run the test program and capture output to file
    echo "- running instrumented test program: ${TESTNAME} ${ARGLIST}"
    java ${OPTIONS} ${BOOTCLASSPATH} ${AGENTPATH} -cp ${CLASSPATH} ${MAINCLASS} ${ARGLIST} > ${RAWFILE}
    if [[ $? -ne 0 ]]; then
        echo "ERROR: java command failure"
        echo "java ${OPTIONS} ${BOOTCLASSPATH} ${AGENTPATH} -cp ${CLASSPATH} ${MAINCLASS} ${ARGLIST} > ${RAWFILE}"
        exit_cleanup
        exit 1
    fi

# perform the following in the danparse folder
cd "../danparse"

    # make sure the test was valid
    valid=0
    while IFS='' read -r line || [[ -n "$line" ]]; do
        # check for lines output by the test program for specifying the expected results (or error)
        # !EXPECTED  will specify the expected test output
        # !TESTEXIT  will indicate the test has completed and we can terminate parsing
        # !INVALID   indicates an error occurred in the arguments passed to the test
        if [[ "${line}" == "!EXPECTED"* && valid -eq 0 ]]; then
            valid=1
        elif [[ "${line}" == "!TESTEXIT"* && valid -eq 1 ]]; then
            valid=2
        elif [[ "${line}" == "!INVALID" ]]; then
            echo "Invalid test selection"
            exit_cleanup
            exit 1
        fi
    done < "${RAWFILE}"
    if [[ valid -ne 2 ]]; then
        echo "ERROR: Expected messages not found"
        exit_cleanup
        exit 1
    fi

    # now we read through the output file and extract the essentials
    java -jar dist/danparse.jar ${TESTMODE} ${RAWFILE} ${OUTFILE}
    status=$(head -n 1 ${OUTFILE})
    echo "Test result: ${status}"

exit 0

