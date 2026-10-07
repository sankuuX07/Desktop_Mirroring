#include "Logger.h"
#include <windows.h>
#include <shlobj.h>   // SHGetFolderPathW, CSIDL_APPDATA
#include <chrono>
#include <ctime>
#include <iomanip>
#include <sstream>

namespace SanskyStream {

Logger& Logger::GetInstance() {
    static Logger instance;
    return instance;
}

void Logger::Log(Level level, const std::string& message) {
    std::lock_guard<std::mutex> lock(m_mutex);

    // Get current time
    auto now = std::chrono::system_clock::now();
    auto in_time_t = std::chrono::system_clock::to_time_t(now);
    std::tm bt{};
    localtime_s(&bt, &in_time_t);
    std::stringstream ss;
    ss << std::put_time(&bt, "%Y-%m-%d %X");

    std::string levelStr;
    switch (level) {
        case Level::Info:    levelStr = "[INFO] "; break;
        case Level::Warning: levelStr = "[WARN] "; break;
        case Level::Error:   levelStr = "[ERROR]"; break;
    }

    std::string fullMessage = ss.str() + " " + levelStr + " " + message + "\n";

    // Output to console (effective in Debug / console subsystem builds)
    std::cout << fullMessage;

    // Output to debugger
    OutputDebugStringA(fullMessage.c_str());

    // ---------------------------------------------------------------------------
    // Output to log file (%APPDATA%\SanskyStream\sansky.log)
    // Open lazily on first call — the mutex is already held so this is safe.
    // ---------------------------------------------------------------------------
    if (!m_logFileOpened) {
        m_logFileOpened = true;
        wchar_t appData[MAX_PATH] = {};
        if (SUCCEEDED(SHGetFolderPathW(nullptr, CSIDL_APPDATA, nullptr, 0, appData))) {
            std::wstring dir = std::wstring(appData) + L"\\SanskyStream";
            CreateDirectoryW(dir.c_str(), nullptr); // no-op if already exists
            std::wstring logPath = dir + L"\\sansky.log";
            m_logFile.open(logPath, std::ios::out | std::ios::app);
        }
    }
    if (m_logFile.is_open()) {
        m_logFile << fullMessage;
        m_logFile.flush();
    }
}

} // namespace SanskyStream
