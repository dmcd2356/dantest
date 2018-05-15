/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package danparse;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 *
 * @author dmcd2356
 */
public class Danparse {

  private static final String NEWLINE = System.getProperty("line.separator");

  // inputs that generate the specified new state
  private static enum stateType {
    NONE,       // starting point
    ERROR,      // an error occurred
    EXIT,       // !TESTEXIT
    ENTRY_II,   // agent output for instrumented to instrumented   EnterMethod
    ENTRY_IU,   // agent output for instrumented to uninstrumented EnterMethod
    ENTRY_UI,   // agent output for uninstrumented to instrumented EnterMethod
    LEAVE_II,   // agent output for instrumented to instrumented   LeaveMethod
    LEAVE_IU,   // agent output for instrumented to uninstrumented LeaveMethod
    LEAVE_UI,   // agent output for uninstrumented to instrumented LeaveMethod
    CALL,       // CALL message
    RETURN,     // RETURN message
    // AGENT message type (these are the agent callbackes to the instrumented code)
    addBooleanParameter,
    addCharParameter,
    addByteParameter,
    addShortParameter,
    addIntegerParameter,
    addLongParameter,
    addFloatParameter,
    addDoubleParameter,
    addObjectParameter,
    addArrayParameter,
    beginFrame,
    removeParams,
    createFrame,
    popFrame,
    popFrameAndPush,
    pushIntegralType,
    pushLonglType,
    pushFloatType,
    pushDoubleType,
    pushReferenceType,
    pushArrayType,
  };

  private static String[] expected;       // the expected response
  private static int     linenum;         // the line number being processed
  private static int     showMessages;    // 1 to print state messages, 2 to print all messages
  private static boolean bFailure;        // true if test failure occurred
  private static int     stateIndex;      // current index in stateList to next valid state
  private static ArrayList<StateInfo> stateList; // list of state changes expected
  private static ArrayList<StateInfo> ignoreList; // list of states to ignore

  public static class StateInfo {
    stateType state;          // the next valid state
    String    arg1;           // associated 1st argument value
    String    arg2;           // associated 2nd argument value
    
    StateInfo(stateType next, String a1, String a2) {
      state = next;
      arg1 = a1;
      arg2 = a2;
    }
    
    StateInfo(stateType next, String a1) {
      state = next;
      arg1 = a1;
      arg2 = "";
    }
    
    StateInfo(stateType next) {
      state = next;
      arg1 = "";
      arg2 = "";
    }
  }
  
  private static void debugPrint(String message) {
    if (showMessages > 0) {
      System.out.println(message);
    }
  }
  
  private static void extendedPrint(String message) {
    if (showMessages > 1) {
      System.out.println("LINE " + linenum + ": " + message);
    }
  }
  
  /**
   * check if contents of string are a valid integer.
   * 
   * @param str - the string value to test
   * @return true if entry was valid integer value.
   */
  private static boolean isValidNumeric(String str) {
    try {
      int value = Integer.parseInt(str);
    } catch (NumberFormatException ex) {
      return false;
    }
    return true;
  }

  /**
   * check if the current line is a valid debug message output.
   * (non-debug output lines can be mixed with this, since the raw output will contain all
   * messages the program directs to standard output).
   * 
   * @param line - a line from the raw output file read.
   * @return true if line was valid debug output format.
   */
  private static boolean isValidDebugMessage(String line) {
    // All debug messages are assumed to start with: "xxxxxxxx [xx:xx.xxx] " to express the line
    // number and timestamp of the message, followed by message type (chars 21-26) and the
    // message contents starting at offset 29.
    return !(line.length() < 30 ||
            line.charAt(9) != '[' || line.charAt(12) != ':' ||
            line.charAt(15) != '.' || line.charAt(19) != ']' ||
            !isValidNumeric(line.substring(0, 8)) ||
            !isValidNumeric(line.substring(10, 11)) ||
            !isValidNumeric(line.substring(13, 14)) ||
            !isValidNumeric(line.substring(16, 18)));
  }
  
  private static void setTestFail(stateType newState, String error) {
    debugPrint("FAIL - " + expected[0] + " :: STATE_" + newState.toString() + " :: " + error);
    bFailure = true;
  }

  private static void setTestPass(StateInfo newState) {
    debugPrint("PASS - " + expected[0] + " :: STATE_" + newState.state.toString() +
        "  " + newState.arg1 + "  " + newState.arg2);
  }

  private static int compareTo(StateInfo state1, StateInfo state2) {
    if (state1.state != state2.state) {
      return 1;
    }

    switch (state1.state) {
      case CALL:
        // these states have 2 associated parameters that must also be verified
        if (!state1.arg1.equals(state2.arg1)) {
          return 2;
        }
        if (!state1.arg2.equals(state2.arg2)) {
          return 3;
        }
        break;
      case ENTRY_II:
      case ENTRY_IU:
      case ENTRY_UI:
      case LEAVE_II:
      case LEAVE_IU:
      case LEAVE_UI:
        // these states have 1 associated parameter that must also be verified
        if (!state1.arg1.equals(state2.arg1)) {
          return 2;
        }
        break;
      default:
        break;
    }
    
    return 0;
  }
    
  /**
   * determines if the specified state to proceed to is valid (as specified in stateList).
   * (sets bFailure to true if invalid)
   * 
   * @param newState - the next state to proceed to
   */
  private static void checkStateNext(StateInfo newState) {
    // ignore entries not pertanent to the test
    if (bFailure || newState == null) {
      return;
    }
    
    // always ignore the following states (should never be thrown)
    if (newState.state == stateType.NONE || newState.state == stateType.ERROR) {
      return;
    }
    
    if (stateList.isEmpty()) {
      setTestFail(newState.state, "No state machine entries set up");
      return;
    }
    
    // ignore any states that were specifically marked to ignore
    boolean canIgnore = false;
    for (StateInfo next : ignoreList) {
      int ret = compareTo(newState, next);
      if (ret == 0) {
        canIgnore = true;
      }
    }

    StateInfo next = stateList.get(stateIndex);

    // also, if this is initial state, ignore a LeaveMethod call from println and if we are
    // at the terminating state, ignore an EnterMethod call from println and the removeParams.
    // These are due to the EXPECTED message setups in the test program.
    boolean bPrintln = newState.arg1.equals("java.io.PrintStream.println");
    if (stateIndex == 0 && newState.state == stateType.LEAVE_UI && bPrintln) {
        canIgnore = true;
    } else if (next.state == stateType.EXIT && (newState.state == stateType.removeParams ||
        (newState.state == stateType.ENTRY_IU && bPrintln))) {
        canIgnore = true;
    }

    int ret = compareTo(newState, next);
    if (ret == 0) {
      setTestPass(newState);
      ++stateIndex;
    } else {
      if (canIgnore) {
        extendedPrint("ignoring state: " + newState.state);
        return;
      }
      switch (ret) {
        default:
        case 1:
          setTestFail(newState.state, "expected: STATE_" + next.state.toString());
          break;
        case 2:
          setTestFail(newState.state, "expected: arg1 = " + next.arg1 + " (was: " + newState.arg1 + ")");
          break;
        case 3:
          setTestFail(newState.state, "expected: arg2 = " + next.arg2 + "  (was: " + newState.arg2 + ")");
          break;
      }
    }
  }

  /**
   * parses the next AGENT line and determines the state it infers.
   * 
   * @param line - the line read from the raw output file
   * @return - the implied state to proceed to
   */
  private static StateInfo parse_AGENT(String line) {
    String[] array = line.split(" "); // UNINSTR has been stripped off the line
    String callback = array[0];
    if (callback.contains(":")) {
      callback = callback.substring(0, callback.indexOf(":"));
    }

    extendedPrint("Debug: AGENT - " + callback);
    switch(callback) { // 1st word is the UNINSTR type specified
      // these have the following addendum: val = <value>, slot = <slot>
      case "addBooleanParameter": return new StateInfo(stateType.addBooleanParameter);
      case "addCharParameter":    return new StateInfo(stateType.addCharParameter);
      case "addByteParameter":    return new StateInfo(stateType.addByteParameter);
      case "addShortParameter":   return new StateInfo(stateType.addShortParameter);
      case "addIntegerParameter": return new StateInfo(stateType.addIntegerParameter);
      case "addLongParameter":    return new StateInfo(stateType.addLongParameter);
      case "addFloatParameter":   return new StateInfo(stateType.addFloatParameter);
      case "addDoubleParameter":  return new StateInfo(stateType.addDoubleParameter);
      case "addObjectParameter":  return new StateInfo(stateType.addObjectParameter);
      case "addArrayParameter":   return new StateInfo(stateType.addArrayParameter);
      //this has the following addendum: maxLocals = <maxLocals>
      case "beginFrame":          return new StateInfo(stateType.beginFrame);
      //this has the following addendum: numParams = <numParams>
      case "removeParams":        return new StateInfo(stateType.removeParams);
      //this has the following addendum: numParams = <numParams>, maxLocals = <maxLocals>
      case "createFrame":         return new StateInfo(stateType.createFrame);
      //this has no addendum
      case "popFrame":            return new StateInfo(stateType.popFrame);
      //this has the following addendum: isVoid = <isVoid>
      case "popFrameAndPush":     return new StateInfo(stateType.popFrameAndPush);
      // these have the following addendum: val = <value>, type = <type>
      case "pushIntegralType":    return new StateInfo(stateType.pushIntegralType);
      case "pushLonglType":       return new StateInfo(stateType.pushLonglType);
      case "pushFloatType":       return new StateInfo(stateType.pushFloatType);
      case "pushDoubleType":      return new StateInfo(stateType.pushDoubleType);
      case "pushReferenceType":   return new StateInfo(stateType.pushReferenceType);
      case "pushArrayType":       return new StateInfo(stateType.pushArrayType);
      default:
        break;
    }
    
    debugPrint("ERROR line " + linenum + ": Unknown UNINSTR type = " + array[0]);
    return new StateInfo(stateType.ERROR);
  }

  /**
   * parses the next raw output line and determines the state it infers, then checks the next
   * state for validity.
   * 
   * @param line - the line read from the raw output file
   */
  private static StateInfo parseAgentLine(String line) {
    
    stateType newState = stateType.NONE;
    String threadid, className, methodName, signature;
    
    // catagorize the expected type
    if (line.length() >= 61) {
      String type = line.substring(0, 32);
      String entry = line.substring(34, 40);
      switch (type) {
        case "Instrumented   to instrumented  ":
          if (entry.startsWith("return")) {
            newState = stateType.LEAVE_II;
          } else {
            newState = stateType.ENTRY_II;
          }
          break;
        case "Instrumented   to uninstrumented":
          if (entry.startsWith("return")) {
            newState = stateType.LEAVE_IU;
          } else {
            newState = stateType.ENTRY_IU;
          }
          break;
        case "Uninstrumented to instrumented  ":
          if (entry.startsWith("return")) {
            newState = stateType.LEAVE_UI;
          } else {
            newState = stateType.ENTRY_UI;
          }
          break;
        default:
          debugPrint("ERROR line " + linenum + ": invalid agent entry: '" + type + "'");
          return new StateInfo(stateType.ERROR);
      }
      
      // extract the thread id
      threadid = line.substring(44);
      int offset = threadid.indexOf(",");
      if (offset <= 0) {
        debugPrint("ERROR line " + linenum + ": missing ',' following threadid: " + threadid);
        return new StateInfo(stateType.ERROR);
      }
      className = threadid.substring(offset+1);
      threadid = threadid.substring(0, offset);
        
      // extract the class, method and signature
      offset = className.indexOf(";");
      if (offset <= 0) {
        debugPrint("ERROR line " + linenum + ": missing ';' following class: " + threadid);
        return new StateInfo(stateType.ERROR);
      }
      methodName = className.substring(offset+1);
      className = className.substring(2, offset); // eliminate the leading space & 'L' char
      className = className.replace('/', '.');
      
      offset = methodName.indexOf("(");
      if (offset <= 0) {
        debugPrint("ERROR line " + linenum + ": missing '(' following method: " + threadid);
        return new StateInfo(stateType.ERROR);
      }
      signature = methodName.substring(offset);
      methodName = methodName.substring(0, offset);

      methodName = className + "." + methodName;

      extendedPrint("AgentCallback: " + newState + ", methodName = " + methodName);
      return new StateInfo(newState, methodName);
    }

    return null;
  }
  
  /**
   * parse a line from the debug file to see if we have the start condition.
   * This line is output by DanTest itself rather than the debug output, but will still be
   * included in the standard output that is saved as the captured raw file.
   * This provides the type of test being performed and any additional parameters needed to define
   * the test conditions.
   */
  private static void createExpectedList(String line) {
    expected = line.split(" ");
    String expType = expected[0];             // the expected test type to run
    String caller  = expected[1];             // the method making the call
    String callee  = expected[2];             // the called method

    switch (expType) {
      case "II":
        // setup valid states for test
        stateList.add(new StateInfo(stateType.ENTRY_II, callee));
        stateList.add(new StateInfo(stateType.createFrame));
        stateList.add(new StateInfo(stateType.CALL, callee, caller));
        stateList.add(new StateInfo(stateType.RETURN));
        stateList.add(new StateInfo(stateType.LEAVE_II, callee));
        stateList.add(new StateInfo(stateType.popFrameAndPush));
        break;
      case "IU":
        // setup valid states for test
        // TODO:
        debugPrint("ERROR: " + expType + " is not implemented!");
        break;
      case "UI":
        // setup valid states for test
        // TODO:
        debugPrint("ERROR: " + expType + " is not implemented!");
        break;
      default:
        expType = "- invalid type " + expType;
        break;
    }

    extendedPrint("NEW TEST - EXPECTING: " + expType + "  " + caller + "  " + callee);
  }
  
  /**
   * parse a line from the debug file to see if we have the start condition.
   * This line is output by DanTest itself rather than the debug output, but will still be
   * included in the standard output that is saved as the captured raw file.
   * This provides the type of test being performed and any additional parameters needed to define
   * the test conditions.
   */
  private static void completeExpectedList() {
    stateList.add(new StateInfo(stateType.EXIT));

    if (!ignoreList.isEmpty()) {
      extendedPrint("ignore list: ");
      for (StateInfo state : ignoreList) {
        extendedPrint("- " + state.state + "  " + state.arg1 + "  " + state.arg2);
      }
    }
    extendedPrint("expected list: ");
    for (StateInfo state : stateList) {
      extendedPrint("- " + state.state + "  " + state.arg1 + "  " + state.arg2);
    }
  }
  
  /**
   * @param args the command line arguments
   */
  public static void main(String[] args) {
    showMessages = 0;
    linenum = 0;
    bFailure = false;
    stateIndex = 0;
    stateList = new ArrayList<>();
    ignoreList = new ArrayList<>();

    String inputfilename = "";
    String outputfilename = "";
    boolean bExpectedSet = false;
    
    if (args.length < 2) {
      System.out.println("Usage: DanParse [-t] <inputfile> <outputfile>");
      System.exit(0);
    }
    
    // get user args
    for (String arg : args) {
      // check for test mode
      if (arg.equals("-t")) {
        showMessages = 1;
      } else if (arg.equals("-T")) {
        showMessages = 2;
      } else if (inputfilename.isEmpty()) {
        inputfilename = arg;
      } else {
        outputfilename = arg;
      }
    }

    // delete any pre-existing output file
    File file = new File(outputfilename);
    if (file.exists()) {
      file.delete();
    }
    
    // read and parse the input file
    file = new File(inputfilename);
    BufferedReader br;
    try {
      br = new BufferedReader(new FileReader(file));
      String line;
      boolean end = false;
      while ((line = br.readLine()) != null && !bFailure) {
        linenum++;
        
        // get the 1st word of the line to see if it matches one of the test program imbedded messages
        String keyword = line;
        int offset = keyword.indexOf(" ");
        if (offset > 0) {
          keyword = keyword.substring(0, offset);
        }

        // these are messages implanted in the test to define the expected output of the test
        if (keyword.equals("!TESTEXIT")) {
          extendedPrint("TESTEXIT");
          checkStateNext(new StateInfo(stateType.EXIT));
          end = true;
          break; // exit readLine loop
        }
        else if (keyword.startsWith("!EXPECTED")) {
          if (bExpectedSet) {
            System.out.println("ERROR: EXPECTED msg found after list completed on line: " + linenum);
            System.exit(1);
          }

          boolean bContinued = false;
          // check if we have a multiple-line configuration list
          if (keyword.equals("!EXPECTED+")) {
            bContinued = true;
          }
          line = (offset <= 0) ? line : line.substring(offset).trim();
          if (bExpectedSet) {
            // if a previous test was running, this will do an auto-TESTEXIT prior to loading next test
            checkStateNext(new StateInfo(stateType.EXIT));
            if (bFailure) {
              break;
            }
            // now, reset expected conditions for next test (if any)
            bExpectedSet = false;
            stateIndex = 0;
            stateList.clear();
            ignoreList.clear();
          }

          // setup the state machine for handling the debug info following it
          createExpectedList(line);
            
          // if this is the final expected definition, terminate the list of valid states
          if (!bContinued && !bExpectedSet) {
            completeExpectedList();
            bExpectedSet = true;
          }
        }
        else if (!bExpectedSet) {
          // ignore all non-configuration messages until the expected results have been defined
          //continue;
        }
        else if (isValidDebugMessage(line)) {
          // message is from the enabled danalyzer debug output
          String debugType = line.substring(21, 27).trim();
          line = line.substring(29);
          switch (debugType) {
            case "AGENT":
              checkStateNext(parse_AGENT(line));
              break;
            case "CALL":
              String[] arglist = line.split(" ");
              String callee = arglist[1];
              callee = (callee.contains("(")) ? callee.substring(0, callee.indexOf("(")) : callee;
              String caller = arglist[2];
              caller = (caller.contains("(")) ? caller.substring(0, caller.indexOf("(")) : caller;
              extendedPrint("Debug: CALL, " + callee + ", " + caller);
              checkStateNext(new StateInfo(stateType.CALL, callee, caller));
              break;
            case "RETURN":
              extendedPrint("Debug: RETURN");
              checkStateNext(new StateInfo(stateType.RETURN));
              break;
            default:
              break;
          }
        } else {
          // message must be from agent output itself
          checkStateNext(parseAgentLine(line));
        }
      }

      if (!end && !bFailure) {
        System.out.println("WARNING: !TESTEXIT message not found!");
      }
    } catch (IOException ex) {
      System.out.println(ex.getMessage());
      System.exit(1);
    }

    // define the output file response
    String status = bFailure ? "FAIL" : "PASS";
    
    // open the file to write to
    try {
      PrintWriter writer = new PrintWriter(outputfilename);
      writer.println(status);
      writer.close();
    } catch (FileNotFoundException ex) {
      System.out.println(ex.getMessage());
      System.exit(1);
    }
  }
  
}
