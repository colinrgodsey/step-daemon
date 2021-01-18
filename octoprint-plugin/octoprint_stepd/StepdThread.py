import subprocess
import os
import os.path
import shutil
import stat

from git import Repo
from os import path
from threading import Thread

repo_url = 'https://github.com/colinrgodsey/step-daemon.git'

class StepdThread(Thread):
  def __init__(self, base_path, logger, settings, running_cb):
    Thread.__init__(self)

    self.base_path = base_path
    self.logger = logger
    self.settings = settings
    self.running_cb = running_cb

    self.running = False
    self.process = None
    self.timeout = 30

    self.repo_path = os.path.join(self.base_path, 'repo')
    self.bin_path = os.path.join(self.base_path, 'stepd')

  ##~~ other stuff

  def init_stepd_repo(self):
    self.logger.info("Init stepd at " + self.repo_path)

    repo = Repo.clone_from(repo_url, self.repo_path)

    return repo

  def build_failed(self):
    self.logger.error("Build failed!")
    self.logger.info('Cleaning up')

  def go_build(self):
    env = os.environ.copy()
    env['GOARM'] = '7' # rpi go often has wrong arm version
    process = subprocess.Popen(['go', 'build', '-a', './cmd/stepd'],
                               stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE,
                               env=env,
                               universal_newlines=True,
                               cwd=self.repo_path)

    while True:
      self.logger.info('GO: %s', process.stdout.readline().strip())
      self.logger.info('GO: %s', process.stderr.readline().strip())

      return_code = process.poll()
      if return_code is not None:
        for output in process.stdout.readlines():
          self.logger.info('GO: %s', output.strip())
        for output in process.stderr.readlines():
          self.logger.info('GO: %s', output.strip())
        break

    return return_code is 0

  def run(self):
    self.logger.info("Checking for stepd updates...")

    new_checkout = False
    if path.exists(self.repo_path):
      repo = Repo(self.repo_path)
    else:
      self.logger.info("Initializing new install")
      repo = self.init_stepd_repo()
      new_checkout = True

    old_commit = repo.active_branch.commit

    for fetch_info in repo.remotes.origin.fetch():
      remote = repo.remotes.origin.refs.master.commit
      #self.logger.info("Remote at %s, local was at %s" % (repo.active_branch.commit, old_commit))
      self.logger.info("Remote at %s, local was at %s" % (remote, old_commit))
      if remote != old_commit or new_checkout:
        self.logger.info("Changes detected, triggering build...")
        repo.active_branch.set_reference(repo.remotes.origin.refs.master)
        repo.head.reset(index=True, working_tree=True)
        break
      else:
        self.logger.info("Build is already up to date.")
        self.running_cb()
        return

    if self.go_build():
      bin_path_target = os.path.join(self.repo_path, 'stepd')

      self.logger.info('Copying bin to ' + str(self.bin_path))
      shutil.copyfile(bin_path_target, self.bin_path)

      st = os.stat(self.bin_path)
      os.chmod(self.bin_path, st.st_mode | stat.S_IEXEC)
      self.running_cb()
    else:
      self.build_failed()

