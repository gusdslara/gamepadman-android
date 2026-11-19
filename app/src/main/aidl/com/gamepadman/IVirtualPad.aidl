package com.gamepadman;

// Runs inside the privileged (shell/root) process started by Shizuku.
interface IVirtualPad {
    // Reserved by Shizuku: called when the service is destroyed.
    void destroy() = 16777114;

    // Creates the virtual HID gamepad. Returns 0 on success or a positive errno.
    int open(int style, int vendor, int product, int version, String name) = 1;

    // Sends one input report (fire-and-forget, never blocks the caller).
    oneway void send(in byte[] report) = 2;

    // Removes the virtual gamepad.
    void close() = 3;

    // Human readable diagnostics (uid, last error, open state).
    String diag() = 4;
}
