package biz.playr;

public enum MemoryStatus {
    OK, // enough memory available
    MEDIUM, // memory is down but no problems are expected (free memory that is not used)
    LOW, // memory is low (free as much memory as possible)
    CRITICAL // memory is very low (take whatever measure possible to free up memory to prevent the app being killed by Android)
}
