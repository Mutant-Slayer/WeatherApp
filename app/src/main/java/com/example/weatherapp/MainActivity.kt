package com.example.weatherapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.weatherapp.models.WeatherResponse
import com.example.weatherapp.network.ApiClient
import com.example.weatherapp.network.ApiInterface
import com.example.weatherapp.utils.Constants
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsResponse
import com.google.android.gms.location.SettingsClient
import com.google.android.gms.tasks.Task
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var isLocationPermissionGranted = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var progressDialog: Dialog? = null

    companion object {
        private const val REQUEST_CHECK_SETTINGS = 0x1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                isLocationPermissionGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION]
                    ?: isLocationPermissionGranted
            }

        requestPermission()
    }

    private fun requestPermission() {
        isLocationPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val permissionRequest: MutableList<String> = mutableListOf()

        if (isLocationPermissionGranted.not()) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                AlertDialog.Builder(this)
                    .setMessage("We need your location permission to provide weather updates.")
                    .setPositiveButton("OK") { _, _ ->
                        permissionRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (permissionRequest.isNotEmpty()) {
                            permissionLauncher.launch(permissionRequest.toTypedArray())
                        }
                    }
                    .create()
                    .show()
            } else {
                permissionRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (permissionRequest.isNotEmpty()) {
                permissionLauncher.launch(permissionRequest.toTypedArray())
            }
        } else {
            requestLocationData()
        }
    }

    @Suppress("MissingPermission")
    private fun requestLocationData() {

        val locationRequest = LocationRequest()
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
        val settingsClient: SettingsClient = LocationServices.getSettingsClient(this)
        val task: Task<LocationSettingsResponse> =
            settingsClient.checkLocationSettings(builder.build())


        task.addOnSuccessListener { _ ->
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.myLooper()
            )
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS)
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Ignore the error.
                }
            } else {
                Log.d("Anas", "Failed to get location data ${exception.message}")
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation
            val latitude = location.latitude
            val longitude = location.longitude
            getLocationWeatherData(latitude, longitude)
        }
    }

    private fun getLocationWeatherData(latitude: Double, longitude: Double) {
        if (Constants.isNetworkAvailable(this)) {
            Toast.makeText(this, "Internet connection available", Toast.LENGTH_SHORT).show()

            val apiService: ApiInterface = ApiClient.instance.create(ApiInterface::class.java)

            val response = apiService.getWeatherData(
                latitude,
                longitude,
                Constants.METRIC_UNIT,
                Constants.API_KEY
            )

            showCustomProgressDialog()

            response.enqueue(object : Callback<WeatherResponse> {
                override fun onResponse(
                    call: Call<WeatherResponse>,
                    response: Response<WeatherResponse>
                ) {
                    if (response.isSuccessful) {
                        hideProgressDialog()
                        val weatherResponse = response.body()
                        if (weatherResponse != null) {
                            setUpUi(weatherResponse)
                        }
                        Log.i("Anas", "Response: $weatherResponse")
                    } else {
                        Log.e("Anas", "Error: ${response.errorBody().toString()}")
                    }
                }

                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                    hideProgressDialog()
                    Log.e("Anas", "Error: ${t.message}")
                }
            })


        } else {
            Toast.makeText(this, "No internet connection available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCustomProgressDialog() {
        progressDialog = Dialog(this)
        progressDialog!!.setContentView(R.layout.dialog_custom_progress)
        progressDialog!!.show()
    }

    private fun hideProgressDialog() {
        if (progressDialog != null) {
            progressDialog!!.dismiss()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setUpUi(weatherResponse: WeatherResponse) {
        for (i in weatherResponse.weather.indices) {
            findViewById<TextView>(R.id.tvMain).text = weatherResponse.weather[i].main
            findViewById<TextView>(R.id.tvMainDescription).text =
                weatherResponse.weather[i].description
            findViewById<TextView>(R.id.tvTemp).text =
                weatherResponse.main.temp.toString() + getUnit(application.resources.configuration.locales.toString())

            findViewById<TextView>(R.id.tvSunriseTime).text =
                convertTime(weatherResponse.sys.sunrise)
            findViewById<TextView>(R.id.tvSunsetTime).text = convertTime(weatherResponse.sys.sunset)

            findViewById<TextView>(R.id.tvHumidity).text =
                weatherResponse.main.humidity.toString() + " percent"
            findViewById<TextView>(R.id.tvMin).text =
                weatherResponse.main.temp_min.toString() + " min"
            findViewById<TextView>(R.id.tvMax).text =
                weatherResponse.main.temp_max.toString() + " max"
            findViewById<TextView>(R.id.tvSpeed).text = weatherResponse.wind.speed.toString()
            findViewById<TextView>(R.id.tvName).text = weatherResponse.name
            findViewById<TextView>(R.id.tvCountry).text = weatherResponse.sys.country

            when (weatherResponse.weather[i].icon) {
                "01d" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.sunny)
                "02d" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.cloud)
                "03d" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.cloud)
                "04d" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.cloud)
                "04n" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.cloud)
                "10d" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.rain)
                "11d" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.snowflake)
                "01n" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.cloud)
                "02n" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.cloud)
                "03n" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.cloud)
                "10n" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.cloud)
                "11n" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.rain)
                "13n" -> findViewById<ImageView>(R.id.ivMain).setImageResource(R.drawable.snowflake)
            }
        }
    }

    private fun getUnit(value: String): String {
        return if (value == "US" || value == "LR" || value == "MM") {
            "°F"
        } else "°C"
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.actionRefresh -> {
                requestLocationData()
                true
            }

            else -> {
                return super.onOptionsItemSelected(item)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CHECK_SETTINGS -> {
                if (resultCode == Activity.RESULT_OK) {
                    requestLocationData()
                }
            }
        }
    }

    private fun convertTime(time: Long): String {
        val date = Date(time * 1000L)
        val dateFormat = SimpleDateFormat("HH:mm", Locale.UK)
        dateFormat.timeZone = TimeZone.getDefault()
        return dateFormat.format(date)
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}