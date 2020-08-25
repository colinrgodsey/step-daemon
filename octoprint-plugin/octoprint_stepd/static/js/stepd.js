/*
 * View model for OctoPrint-stepd
 *
 * Author: Colin Godsey
 * License: Apache2.0
 */
$(function() {
  function StepdViewModel(parameters) {
    var self = this;

    self.settingsViewModel = parameters[0];

    self.status = ko.observable("Waiting for status update");

    self.updating = ko.observable(true);
    self.success = ko.observable(false);

    self.failed = ko.computed(function() {
      return !self.updating() && !self.success();
    }, this);

    self.onBeforeBinding = function() {
      self.settings = self.settingsViewModel.settings;
    };

    ///////////////////////////////////////

    self.isUpdatingStatus = false;
    self.updateStatus = function() {
      if (self.isUpdatingStatus) return;

      self.isUpdatingStatus = true;

      $.ajax({
        url: API_BASEURL + "plugin/stepd",
        type: "GET",
        success: function(response) {
          self.status(response.status);
          self.updating(response.updating);
          self.success(response.success);

          console.log("stepd status " + self.status());
        },
        complete: function() {
          self.isUpdatingStatus = false;
        }
      });
    };

    setInterval(function() {
      self.updateStatus();
    }, 3000);
    self.updateStatus();
  }

  OCTOPRINT_VIEWMODELS.push({
    construct: StepdViewModel,
    dependencies: ["settingsViewModel"],
    elements: [
      document.getElementById("tab_plugin_stepd"),
      document.getElementById("settings_plugin_stepd")
    ]
  });
});