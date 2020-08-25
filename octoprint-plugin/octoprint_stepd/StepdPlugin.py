from __future__ import absolute_import

import serial
import flask
import octoprint.plugin
import os.path

from .StepdThread import StepdThread
from .StepdService import StepdService

class StepdPlugin(octoprint.plugin.SettingsPlugin,
                  octoprint.plugin.AssetPlugin,
                  octoprint.plugin.TemplatePlugin,
                  octoprint.plugin.SimpleApiPlugin,
                  #octoprint.plugin.RestartNeedingPlugin,
                  octoprint.plugin.StartupPlugin):

  thread = None

  ##~~ SettingsPlugin mixin

  def get_settings_defaults(self):
    return dict(
      sjerk='1e8, 1e7, 1e6, 1e10',
      format='SP_4x2_256',
      tickrate=61440,
      baud=500000,
      port='/dev/ttyUSB0',
      bedx=200,
      bedy=200
    )

  ##~~ SimpleApiPlugin mixin

  def get_api_commands(self):
    return dict(
      update=[]
    )

  def on_api_command(self, command, data):
    if command == "update":
      self.update_stepd()

  def on_api_get(self, request):
    return flask.jsonify(
      status=self.get_current_status(),
      running=self.is_running(),
      updating=self.is_updating(),
      settings=self._settings.get_all_data()
    )

  ##~~ TemplatePlugin mixin

  def get_template_vars(self):
    return dict(
      status=self.get_current_status(),
      config_path=os.path.join(self.get_plugin_data_folder(), 'config.conf')
    )

  def get_template_configs(self):
    return []

  ##~~ AssetPlugin mixin

  def get_assets(self):
    # Define your plugin's asset files to automatically include in the
    # core UI here.
    return dict(
      js=["js/stepd.js"],
      css=["css/stepd.css"],
      less=["less/stepd.less"]
    )

  ##~~ Softwareupdate hook

  def get_update_information(self):
    # Define the configuration for your plugin to use with the Software Update
    # Plugin here. See https://docs.octoprint.org/en/master/bundledplugins/softwareupdate.html
    # for details.
    return dict(
      stepd=dict(
        displayName="Stepd Plugin",
        displayVersion=self._plugin_version,

        # version check: github repository
        type="github_release",
        user="colinrgodsey",
        repo="OctoPrint-stepd",
        current=self._plugin_version,

        # update method: pip
        #pip="https://github.com/colinrgodsey/OctoPrint-stepd/archive/{target_version}.zip"
        pip="https://raw.githubusercontent.com/colinrgodsey/maven/master/step-daemon/octoprint-plugin/latest.zip"
      )
    )

  ##~~ Serial factory hook

  def serial_factory_hook(self, comm_instance, port, baudrate, read_timeout, *args, **kwargs):
    comm_instance._log("Connecting to Step Daemon")

    if self.is_running():
      msg = 'Step Daemon is still updating.'
      comm_instance._log(msg)
      raise Exception(msg)

    return StepdService(self.get_plugin_data_folder(), self._logger,
                        self._settings.get_all_data(), port, baudrate)

  ##~~ StartupPlugin mixin

  def on_after_startup(self):
    self.update_stepd()

  ##~~ Other stuff

  def update_stepd(self):
    if self.is_running():
      self.thread.close()

    self.thread = StepdThread(self.get_plugin_data_folder(), self._logger,
                              self._settings.get_all_data(), self.on_stepd_start)
    self.thread.start()

  def is_running(self):
    return self.thread and self.thread.running

  def is_updating(self):
    return self.thread and self.thread.is_alive() and not self.thread.running

  def get_current_status(self):
    if not self.thread:
      return "Starting..."
    elif self.thread.running:
      return "Running..."
    elif self.thread.is_alive():
      return "Updating..."
    else:
      return "Server has crashed. Please restart OctoPrint."

  def on_stepd_start(self):
    self._logger.info('Process ready, connecting now.')
    self._printer.connect()







