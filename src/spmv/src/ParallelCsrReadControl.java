import com.maxeler.maxcompiler.v2.statemachine.*;
import com.maxeler.maxcompiler.v2.statemachine.types.*;
import com.maxeler.maxcompiler.v2.statemachine.manager.*;
import com.maxeler.maxcompiler.v2.managers.DFEManager;

public class ParallelCsrReadControl extends ManagerStateMachine {

  private enum Mode {
    //Flush,
    VectorLoad,
    //RequestRead,
    ReadingLength,
    OutputtingCommands,
    Done,
  }

  private final DFEsmStateEnum<Mode> mode;

    private final DFEsmPullInput iLength;
    //private final DFEsmPushOutput oFlush;
    private final DFEsmInput vectorLoadCycles, nRows, nPartitions, nIterations;
    private final DFEsmPushOutput oControl;
    private final DFEsmStateValue oControlData;

    private final DFEsmStateValue outValid, prevData, readLength, iLengthReady;
    private final DFEsmStateValue readMaskData;
    private final DFEsmStateValue cycleCounter, rowLengthData;
    private final DFEsmStateValue firstReadPosition;
    private final DFEsmStateValue rowsProcessed, vectorLoadCommands, partitionsProcessed, totalOutputs;
    private final DFEsmStateValue iterationsProcessed;
    //private final DFEsmStateValue flushData;

    private final DFEsmStateValue crtPos, toread;
    private final int inputWidth;
    private final boolean dbg;

    public ParallelCsrReadControl(DFEManager owner, int inputWidth, boolean dbg) {
      super(owner);
      this.inputWidth = inputWidth;
      this.dbg = dbg;

      mode = state.enumerated(Mode.class, Mode.VectorLoad);
      iLength = io.pullInput("length", dfeUInt(32));
      vectorLoadCycles = io.scalarInput("vectorLoadCycles", dfeUInt(32));
      nRows = io.scalarInput("nrows", dfeUInt(32));
      nPartitions = io.scalarInput("nPartitions", dfeUInt(32));
      nIterations = io.scalarInput("nIterations", dfeUInt(32));

      oControl = io.pushOutput("control", dfeUInt(3 + 3 * 32 + inputWidth), 1);
      //oFlush  = io.pushOutput("flush", dfeBool(), 1);

      cycleCounter = state.value(dfeUInt(32), 0);
      crtPos = state.value(dfeUInt(32), 0);
      firstReadPosition = state.value(dfeUInt(32), 0);
      toread = state.value(dfeUInt(32), 0);
      oControlData = state.value(dfeUInt(3 + 3 * 32 + inputWidth), 0);
      //flushData = state.value(dfeBool(), false);
      rowLengthData = state.value(dfeUInt(32), 0);
      outValid = state.value(dfeBool(), false);
      readMaskData = state.value(dfeUInt(inputWidth));
      readLength = state.value(dfeBool(), false);
      iLengthReady = state.value(dfeBool(), false);

      // internal state data, not for output
      rowsProcessed = state.value(dfeUInt(32), 0);
      vectorLoadCommands = state.value(dfeUInt(32), 0);
      partitionsProcessed = state.value(dfeUInt(32), 0);
      iterationsProcessed = state.value(dfeUInt(32), 0);
      totalOutputs = state.value(dfeUInt(32), 0);
      prevData = state.value(dfeUInt(32), 0);
    }

    DFEsmValue outputNotStall() {
      return ~oControl.stall;
    }

    DFEsmValue needToRead() {
      return readLength;
    }

    // should be use both in next state and output function to request a read
    DFEsmValue canRead() {
      return ~iLength.empty & outputNotStall();
    }

    void processRows(DFEsmValue r, DFEsmValue requestRead) {
      rowsProcessed.next <== rowsProcessed + r;
      IF (rowsProcessed === nRows - r) {
        // reset all useful state variables between partitions
        rowsProcessed.next <== 0;
        vectorLoadCommands.next <== 0;
        crtPos.next <== 0;
        toread.next <== 0;
        rowLengthData.next <== 0;
        firstReadPosition.next <== 0;
        cycleCounter.next <== 0;
        prevData.next <== 0;
        readLength.next <== false;
        iLengthReady.next <== false;
        mode.next <== Mode.VectorLoad;
        partitionsProcessed.next <== partitionsProcessed + 1;
        IF (partitionsProcessed === nPartitions - 1) {
          //mode.next <== Mode.Flush;
          partitionsProcessed.next <== 0;
          // XXX will also need to skip padding between partitions
          iterationsProcessed.next <== iterationsProcessed + 1;
          IF (iterationsProcessed === nIterations - 1) {
            mode.next <== Mode.Done;
          }
        }
      } ELSE {
        IF (requestRead) {
          mode.next <== Mode.ReadingLength;
          readLength.next <== true;
        }
      }
    }

    void makeCommand(
        DFEsmValue p_crtPos,
        DFEsmValue p_toread,
        DFEsmValue p_rowLengthData,
        DFEsmValue p_firstReadPosition,
        DFEsmValue p_cycleCounter
        ) {
      DFEsmValue canread = min(inputWidth - p_crtPos, p_toread);
      IF (p_crtPos + canread >= inputWidth) {
        crtPos.next <== 0;
      } ELSE {
        crtPos.next <== p_crtPos + canread;
      }
      toread.next <== p_toread - canread;
      makeOutput(
          p_crtPos === 0 & p_rowLengthData !== 0,
          p_toread - canread === 0,
          buildReadMask(canread),
          p_rowLengthData,
          p_cycleCounter,
          p_firstReadPosition,
          fls());
      cycleCounter.next <== p_cycleCounter + 1;
      IF (p_toread - canread === 0) {
        processRows(one(), tru());
      }
    }

    @Override
    protected void nextState() {
      outValid.next <== false;

      iLengthReady.next <== canRead() & needToRead();
             //disable read request on next cycle, if we request data this cycle
      IF (canRead() & needToRead())
        readLength.next <== false;

      SWITCH (mode) {
        CASE (Mode.Done) {
          // do nothing
        }
        CASE (Mode.VectorLoad) {
          IF (outputNotStall() & ~iLength.empty) {
            vectorLoadCommands.next <== vectorLoadCommands + 1;
            IF (vectorLoadCommands === vectorLoadCycles - 1) {
              mode.next <== Mode.ReadingLength;
              readLength.next <== true;
            }
            makeOutput(
                fls(), fls(), zero(inputWidth), zero(), zero(), crtPos, tru()
                );
          }
        }
        CASE (Mode.ReadingLength) {
          IF (iLengthReady === true) {
            // XXX When merging must ensure output is not stalled in thiss state
            IF (iLength.slice(31, 1) === 1) {
              // this is a run length encoded sequence of empty rows
              toread.next <== 0;
              rowLengthData.next <== 0;
              // cycleCounter will hold the number of empty rows in this sequence
              DFEsmValue emptyRows = iLength.slice(0, 31).cast(dfeUInt(32));
              //cycleCounter.next <== emptyRows;
              // the value of prevData will be maintained from the most recent
              // non-empty row since we don't assign to it when processing an
              // empty row; this is required to correctly determine the lenght of the
              // next non-empty tow

              makeOutput(fls(), tru(), zero(inputWidth), zero(), emptyRows, crtPos, fls());
              processRows(emptyRows, tru());
            } ELSE {
              toread.next <== iLength - prevData;
              rowLengthData.next <== iLength - prevData;
              prevData.next <== iLength;
              cycleCounter.next <== 0;

              mode.next <== Mode.OutputtingCommands;
              makeCommand(crtPos, iLength - prevData, iLength - prevData, crtPos, zero());
              //mode.next <== Mode.OutputtingCommands;
            }
            firstReadPosition.next <== crtPos;
          }
        }
        CASE (Mode.OutputtingCommands) {
          IF (outputNotStall()) {
            makeCommand(crtPos, toread, rowLengthData, firstReadPosition, cycleCounter);
          }
        }
      }
    }

      @Override
      protected void outputFunction() {
        iLength.read <== canRead() & needToRead();

        oControl.valid <== outValid;

        oControl <== oControlData;

        //oFlush <== flushData;

        if (dbg)
          IF (outValid) {
            debug.simPrintf(
                "ReadControl SM -- nPartitions %d, partitionsProcessed %d, vectorLoadCycles %d, vectorLoadCommands %d, rowsProcessed %d, iLength %d, readmask: %d, toread: %d, crtPos: %d, rowLength %d, cycleCounter %d",
                nPartitions, partitionsProcessed, vectorLoadCycles, vectorLoadCommands, rowsProcessed, iLength, readMaskData, toread, crtPos, rowLengthData, cycleCounter);
            debug.simPrintf(
                "totalOutputs %d\n", totalOutputs);
          }
      }

    void makeOutput(
        DFEsmValue readEnable,
        DFEsmValue rowFinished,
        DFEsmValue readMask,
        DFEsmValue rowLength,
        DFEsmValue cycleCounterP,
        DFEsmValue firstReadPositionP,
        DFEsmValue vectorLoad
        //DFEsmValue flush
        )
    {

      outValid.next <== true;

      //flushData.next <== flush;
      oControlData.next <== cat(
          readMask,
          vectorLoad, rowFinished, readEnable,
          rowLength, cycleCounterP, firstReadPositionP);
      totalOutputs.next <== totalOutputs + 1;
    }

    DFEsmValue min(DFEsmValue a, DFEsmValue b){
      DFEsmAssignableValue min = assignable.value(dfeUInt(32));
      IF ( a < b) {
        min <== a;
      } ELSE {
        min <== b;
      }
      return min;
    }

    DFEsmValue buildReadMask(DFEsmValue canread) {
      DFEsmAssignableValue pattern = assignable.value(dfeUInt(64));
      pattern <== 0;
      for (long i = 0; i <= inputWidth; i++)
        IF (canread === i)
          pattern <== (1l << i) - 1l;

      DFEsmAssignableValue newReadMask = assignable.value(dfeUInt(inputWidth));
      newReadMask <== 0;
      for (int i = 0; i <= inputWidth; i++)
        IF (crtPos === i)
          newReadMask <== pattern.shiftLeft(i).cast(dfeUInt(inputWidth));

      return newReadMask;
    }

    DFEsmValue fls() {
      return constant.value(false);
    }

    DFEsmValue tru(){
      return constant.value(true);
    }

    DFEsmValue one() {
      return constant.value(dfeUInt(32), 1);
    }

    DFEsmValue zero() {
      return constant.value(dfeUInt(32), 0);
    }

    DFEsmValue zero(int bitWidth) {
      return constant.value(dfeUInt(bitWidth), 0);
    }

    DFEsmValue cat(DFEsmValue... args) {
      DFEsmValue first = args[0];

      for (int i = 1; i < args.length; i++) {
        first = cat(first, args[i]);
      }

      return first;
    }

    DFEsmValue cat(DFEsmValue left, DFEsmValue right) {
      int bitsLeft = left.getType().getTotalBits();
      int bitsRight = right.getType().getTotalBits();
      int totalBits = bitsRight + bitsLeft;
      DFEsmValueType t = dfeUInt(totalBits);
      return left.cast(t).shiftLeft(bitsRight) + right.cast(t);
    }

}
