#ifndef __TRACEDOCTOR_H_
#define __TRACEDOCTOR_H_

#include "core/bridge_driver.h"
#include "core/clock_info.h"

#include <cstdint>
#include <string>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <memory>
#include <queue>
#include <atomic>
#include <chrono>
#include <numeric>
#include <functional>
#include "tracedoctor_register.h"

struct TRACEDOCTORBRIDGEMODULE_struct {
  uint64_t initDone;
  uint64_t traceEnable;
  uint64_t triggerSelector;
};

class spinlock {
  std::atomic<bool> lock_ = {false};
public:
  void lock() {
    for (;;) {
      if (!lock_.exchange(true, std::memory_order_acquire)) {
        break;
      }
      while (lock_.load(std::memory_order_relaxed));
    }
  }

  void unlock() {
    lock_.store(false, std::memory_order_release);
  }

  bool try_lock() {
    return !lock_.exchange(true, std::memory_order_acquire);
  }
};

#define locktype_t spinlock
//#define locktype_t std::mutex


struct protectedWorker {
  locktype_t lock;
  std::unique_ptr<tracedoctor_worker> worker;
};

struct referencedBuffer {
  char *data;
  unsigned int tokens;
  std::atomic<unsigned int> refs;
};


class tracedoctor_t final : public streaming_bridge_driver_t
{
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  tracedoctor_t(simif_t &sim,
                StreamEngine &stream,
                const TRACEDOCTORBRIDGEMODULE_struct &mmio_addrs,
                int tracerId,
                std::vector<std::string> &args,
                uint32_t stream_idx,
                uint32_t stream_depth,
                unsigned int tokenWidth,
                unsigned int traceWidth,
                const ClockInfo &clock_info);
  ~tracedoctor_t();

  void init();
  void tick();
  bool terminate() { return false; }
  int exit_code() { return 0; }
  void finish() { flush(); };
  void balancedWork(unsigned int const threadIndex);
  void work(unsigned int const threadIndex);

private:
  const TRACEDOCTORBRIDGEMODULE_struct mmio_addrs;
  int streamIdx;
  int streamDepth;

  std::vector<std::thread> workerThreads;
  // Atomics and Mutex are now allowed to be passed around
  // keep them in one place and pass references
  std::vector<std::unique_ptr<struct protectedWorker>> workers;
  std::vector<std::unique_ptr<struct referencedBuffer>> buffers;

  std::vector<std::queue<struct referencedBuffer *>> workQueues;

  locktype_t workQueueLock;
  std::condition_variable_any workQueueCond;
  bool workQueuesMaybeEmpty = true;

  unsigned int bufferIndex = 0;
  unsigned int bufferGrouping = 1;
  unsigned int bufferDepth = 64;
  unsigned int bufferTokenCapacity;
  unsigned int bufferTokenThreshold;
  unsigned long int totalTokens = 0;


  std::chrono::duration<double> tickTime = std::chrono::seconds(0);

  ClockInfo clock_info;
  struct traceInfo info = {};

  bool traceEnabled = false;
  unsigned int traceTrigger = 0;
  int traceThreads = -1;
  bool workerExit = false;

  bool process_tokens(unsigned int const tokens, bool flush = false);
  void flush();
};
#endif // __TRACEDOCTOR_H_
