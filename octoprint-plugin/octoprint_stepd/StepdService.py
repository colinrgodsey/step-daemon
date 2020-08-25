import subprocess
import os
import os.path
import json

from threading import Thread

class StepdService():
  def __init__(self, base_path, logger, settings, device, baudrate):
    self.base_path = base_path
    self.logger = logger
    self.settings = settings
    self.device = device
    self.baudrate = baudrate

    self.timeout = 30
    self.bin_path = os.path.join(self.base_path, 'step-daemon')

    args = [self.bin_path, 'device='+str(self.device), 
            'baud='+str(self.baudrate), 'config=config.json']

    logger.info("Starting service: " + str(args))

    self.update_config()
    self.process = subprocess.Popen(args,
                               stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE,
                               stdin=subprocess.PIPE,
                               universal_newlines=True,
                               cwd=self.base_path)

    StepdServiceLogger(self.process, self.logger).start()

  ##~~ mock Serial methods

  def readline(self, *args, **kwargs):
    return self.process.stdout.readline(*args, **kwargs)

  def write(self, *args, **kwargs):
    return self.process.stdin.write(*args, **kwargs)

  def close(self):
    self.process.kill()

  ##~~ other stuff

  def update_config(self):
    conf_path = os.path.join(self.base_path, 'config.json')

    sjerk = self.settings['sjerk'].split(",")
    sjerk = [float(x) for x in sjerk]

    data = {
      'sjerk': sjerk,
      'format': self.settings['format'],
      'ticks-per-second': int(self.settings['tickrate']),
      'bed-samples-path': './bedlevel.json',

      'bed-max': [self.settings['bedx'], self.settings['bedy']]
    }

    data = json.dumps(data, indent=2, separators=(',', ': '))

    with open(conf_path, "w") as file:
      file.write(data)

class StepdServiceLogger(Thread):
  def __init__(self, process, logger):
    Thread.__init__(self)

    self.logger = logger
    self.process = process

  def run(self):
    while True:
      self.logger.info('GO: %s', self.process.stderr.readline().strip())

      return_code = self.process.poll()
      if return_code is not None:
        for output in self.process.stderr.readlines():
          self.logger.info('ERR: %s', output.strip())
        break

    self.process.stdout.close()
    self.process.stdin.close()
    self.logger.info('service terminated')
