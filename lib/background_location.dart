import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// BackgroundLocation plugin to get background
/// lcoation updates in iOS and Android
class BackgroundLocation {
  // The channel to be used for communication.
  // This channel is also refrenced inside both iOS and Abdroid classes
  static const MethodChannel _channel =
      MethodChannel('com.almoullim.background_location/methods');

  /// Stop receiving location updates
  static Future<dynamic> stopLocationService() async {
    return await _channel.invokeMethod('stop_location_service');
  }

  /// Check if the location update service is running
  static Future<bool> isServiceRunning() async {
    var result = await _channel.invokeMethod('is_service_running');
    return result == true;
  }

  /// Start receiving location updated
  static Future<dynamic> startLocationService(
      {double distanceFilter = 0.0,
      bool forceAndroidLocationManager = false}) async {
    return await _channel
        .invokeMethod('start_location_service', <String, dynamic>{
      'distance_filter': distanceFilter,
      'force_location_manager': forceAndroidLocationManager
    });
  }

  static Future<dynamic> setAndroidNotification(
      {String? title, String? message, String? icon}) async {
    if (Platform.isAndroid) {
      return await _channel.invokeMethod('set_android_notification',
          <String, dynamic>{'title': title, 'message': message, 'icon': icon});
    }
  }

  static Future<dynamic> setAndroidConfiguration(int interval) async {
    if (Platform.isAndroid) {
      return await _channel.invokeMethod('set_configuration', <String, dynamic>{
        'interval': interval.toString(),
      });
    }
  }

  static Future<List<Location>> getBufferedLocations() async {
    if (!Platform.isAndroid) return [];
    final List<dynamic> result =
        await _channel.invokeMethod('get_buffered_locations');
    return result.map((map) => Location.fromMap(map)).toList();
  }

  static Future<void> clearBufferedLocations() async {
    if (!Platform.isAndroid) return;
    await _channel.invokeMethod('clear_buffered_locations');
  }

  /// Get the current location once.
  Future<Location> getCurrentLocation() async {
    var completer = Completer<Location>();

    getLocationUpdates((location) {
      var loc = Location(
        latitude: location.latitude,
        longitude: location.longitude,
        accuracy: location.accuracy,
        altitude: location.altitude,
        bearing: location.bearing,
        speed: location.speed,
        time: location.time,
        isMock: location.isMock,
      );
      completer.complete(loc);
    });

    return completer.future;
  }

  /// Register a function to recive location updates as long as the location
  /// service has started
  static void getLocationUpdates(Function(Location) locationCallback) {
    // add a handler on the channel to receive updates from the native classes
    _channel.setMethodCallHandler((MethodCall methodCall) async {
      if (methodCall.method == 'location') {
        try {
          locationCallback(Location.fromMap(methodCall.arguments));
        } catch (e) {
          //print(e.toString());
        }
      } else if (methodCall.method == 'buffered_locations') {
        try {
          final List<dynamic> locationsData = methodCall.arguments;
          for (final map in locationsData) {
            locationCallback(Location.fromMap(map));
          }
        } catch (e) {
          //print(e.toString());
        }
      }
    });
  }

  static Future<void> setRecordingState(bool isRecording) async {
    if (!Platform.isAndroid) return;
    await _channel
        .invokeMethod('set_recording_state', {'is_recording': isRecording});
  }
}

/// about the user current location
class Location {
  double? latitude;
  double? longitude;
  double? altitude;
  double? bearing;
  double? accuracy;
  double? speed;
  double? time;
  bool? isMock;

  Location({
    @required this.longitude,
    @required this.latitude,
    @required this.altitude,
    @required this.accuracy,
    @required this.bearing,
    @required this.speed,
    @required this.time,
    @required this.isMock,
  });

  factory Location.fromMap(Map<dynamic, dynamic> map) {
    return Location(
      latitude: map['latitude'],
      longitude: map['longitude'],
      altitude: map['altitude'],
      accuracy: map['accuracy'],
      bearing: map['bearing'],
      speed: map['speed'],
      time: map['time'],
      isMock: map['is_mock'],
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'latitude': latitude,
      'longitude': longitude,
      'altitude': altitude,
      'bearing': bearing,
      'accuracy': accuracy,
      'speed': speed,
      'time': time,
      'is_mock': isMock,
    };
  }
}
