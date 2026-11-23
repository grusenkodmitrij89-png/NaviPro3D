package com.navipro.enterprise

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private lateinit var locationManager: LocationManager

    private lateinit var tvModeDetails: TextView
    private lateinit var tvTargetHeader: TextView
    private lateinit var tvTargetSub: TextView
    private lateinit var tvGnssBadge: TextView
    private lateinit var btnSimDrive: MaterialButton
    private lateinit var btnPitchToggle: MaterialButton

    private enum class B2BMode { TAXI, DELIVERY, RESTAURANT }
    private var currentMode = B2BMode.TAXI
    private var currentPitch = 65.0

    private var isSimulating = false
    private val simHandler = Handler(Looper.getMainLooper())
    private var simStep = 0
    private var currentPos = LatLng(47.2225, 39.7187)
    private var currentBearing = 35.0

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startRealGps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        tvModeDetails = findViewById(R.id.tvModeDetails)
        tvTargetHeader = findViewById(R.id.tvTargetHeader)
        tvTargetSub = findViewById(R.id.tvTargetSub)
        tvGnssBadge = findViewById(R.id.tvGnssBadge)
        btnSimDrive = findViewById(R.id.btnSimDrive)
        btnPitchToggle = findViewById(R.id.btnPitchToggle)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            setupNextGen3DStyle(map)
        }

        setupUIControls()
        checkLocationPermission()
    }

    private fun setupNextGen3DStyle(map: MapLibreMap) {
        val styleJson = """
        {
          "version": 8,
          "name": "NaviPro3D-NextGen",
          "sources": {
            "openmaptiles": {
              "type": "vector",
              "url": "https://tiles.openfreemap.org/planet"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#0A0E17" }
            },
            {
              "id": "water",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "water",
              "paint": { "fill-color": "#0E2238" }
            },
            {
              "id": "park",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "park",
              "paint": { "fill-color": "#0E261E" }
            }
          ]
        }
        """.trimIndent()

        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            val roadCasing = LineLayer("road-casing", "openmaptiles").apply {
                sourceLayer = "transportation"
                setProperties(
                    lineColor("#2B3548"),
                    lineWidth(
                        interpolate(
                            exponential(1.5f), zoom(),
                            stop(14, 4f),
                            stop(18, 26f)
                        )
                    ),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND)
                )
            }
            style.addLayer(roadCasing)

            val roadAsphalt = LineLayer("road-asphalt", "openmaptiles").apply {
                sourceLayer = "transportation"
                setProperties(
                    lineColor("#161D2B"),
                    lineWidth(
                        interpolate(
                            exponential(1.5f), zoom(),
                            stop(14, 3f),
                            stop(18, 22f)
                        )
                    ),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND)
                )
            }
            style.addLayer(roadAsphalt)

            val gostDashedLines = LineLayer("gost-dashed-lanes", "openmaptiles").apply {
                sourceLayer = "transportation"
                setFilter(in_(get("class"), literal("primary"), literal("secondary"), literal("tertiary"), literal("trunk")))
                setProperties(
                    lineColor("#CBD5E1"),
                    lineWidth(
                        interpolate(
                            linear(), zoom(),
                            stop(15, 0.5f),
                            stop(18, 1.6f)
                        )
                    ),
                    lineDasharray(arrayOf(3.0f, 3.0f))
                )
            }
            style.addLayer(gostDashedLines)

            val gostDoubleSolid = LineLayer("gost-double-solid", "openmaptiles").apply {
                sourceLayer = "transportation"
                setFilter(in_(get("class"), literal("primary"), literal("trunk")))
                setProperties(
                    lineColor("#FFD600"),
                    lineWidth(
                        interpolate(
                            linear(), zoom(),
                            stop(15, 0.8f),
                            stop(18, 2.2f)
                        )
                    )
                )
            }
            style.addLayer(gostDoubleSolid)

            val buildings3D = FillExtrusionLayer("3d-buildings", "openmaptiles").apply {
                sourceLayer = "building"
                minZoom = 14.5f
                setProperties(
                    fillExtrusionColor(
                        interpolate(
                            linear(),
                            coalesce(get("render_height"), literal(12)),
                            stop(0, "#1E293B"),
                            stop(25, "#28374D"),
                            stop(60, "#384E6E")
                        )
                    ),
                    fillExtrusionHeight(
                        coalesce(get("render_height"), literal(12))
                    ),
                    fillExtrusionBase(
                        coalesce(get("render_min_height"), literal(0))
                    ),
                    fillExtrusionOpacity(0.92f)
                )
            }
            style.addLayer(buildings3D)

            setupB2BLayers(style)
            moveCamera3D(currentPos, currentBearing, 17.5, currentPitch)
        }
    }

    private fun setupB2BLayers(style: Style) {
        val routeCoords = listOf(
            Point.fromLngLat(39.7187, 47.2225),
            Point.fromLngLat(39.7210, 47.2242),
            Point.fromLngLat(39.7245, 47.2255),
            Point.fromLngLat(39.7278, 47.2270)
        )
        val routeSource = GeoJsonSource("b2b-route-source", Feature.fromGeometry(LineString.fromLngLats(routeCoords)))
        style.addSource(routeSource)

        val routeLayer = LineLayer("b2b-route-layer", "b2b-route-source").apply {
            setProperties(
                lineColor("#FFD600"),
                lineWidth(6f),
                lineCap(Property.LINE_CAP_ROUND)
            )
        }
        style.addLayer(routeLayer)

        val carSource = GeoJsonSource("car-source", Feature.fromGeometry(Point.fromLngLat(currentPos.longitude, currentPos.latitude)))
        style.addSource(carSource)

        val carBeaconOuter = CircleLayer("car-beacon-outer", "car-source").apply {
            setProperties(
                circleRadius(18f),
                circleColor("#00E5FF"),
                circleOpacity(0.28f)
            )
        }
        val carCore = CircleLayer("car-core", "car-source").apply {
            setProperties(
                circleRadius(8f),
                circleColor("#00E5FF"),
                circleStrokeWidth(3f),
                circleStrokeColor("#FFFFFF")
            )
        }
        style.addLayer(carBeaconOuter)
        style.addLayer(carCore)
    }

    private fun setupUIControls() {
        val toggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.toggleModeGroup)
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnModeTaxi -> applyB2BMode(B2BMode.TAXI)
                R.id.btnModeDelivery -> applyB2BMode(B2BMode.DELIVERY)
                R.id.btnModeRestaurant -> applyB2BMode(B2BMode.RESTAURANT)
            }
        }

        btnSimDrive.setOnClickListener {
            isSimulating = !isSimulating
            if (isSimulating) {
                btnSimDrive.text = "СТОП"
                tvGnssBadge.text = "SIMULATION // 3D AUTOPILOT"
                startSimulation()
            } else {
                btnSimDrive.text = "ТЕСТ-ДРАЙВ"
                tvGnssBadge.text = "GNSS + KALMAN: 3D FIX"
                simHandler.removeCallbacksAndMessages(null)
            }
        }

        btnPitchToggle.setOnClickListener {
            currentPitch = if (currentPitch > 40.0) 0.0 else 65.0
            btnPitchToggle.text = if (currentPitch > 0) "3D УГОЛ 65°" else "2D ВИД 0°"
            moveCamera3D(currentPos, currentBearing, 17.5, currentPitch)
        }

        findViewById<MaterialButton>(R.id.btnMyLocation).setOnClickListener {
            isSimulating = false
            btnSimDrive.text = "ТЕСТ-ДРАЙВ"
            simHandler.removeCallbacksAndMessages(null)
            checkLocationPermission()
            moveCamera3D(currentPos, currentBearing, 17.8, currentPitch)
        }
    }

    private fun applyB2BMode(mode: B2BMode) {
        currentMode = mode
        val style = mapLibreMap?.style ?: return
        val routeLayer = style.getLayerAs<LineLayer>("b2b-route-layer")
        val buildingLayer = style.getLayerAs<FillExtrusionLayer>("3d-buildings")

        when (mode) {
            B2BMode.TAXI -> {
                tvModeDetails.text = "Режим Такси: Карманы посадки без штрафов (3.27) + 3D-подъезды"
                tvTargetHeader.text = "ТОЧКА ПОСАДКИ: Подъезд №3 • Карман разрешен"
                tvTargetHeader.setTextColor(Color.parseColor("#FFD600"))
                tvTargetSub.text = "Полоса 2 (Прямо) • Шлагбаум: Авто-открытие по звонку • 140 м"
                routeLayer?.setProperties(lineColor("#FFD600"))
                buildingLayer?.setProperties(
                    fillExtrusionColor(
                        interpolate(linear(), coalesce(get("render_height"), literal(12)),
                            stop(0, "#1E293B"), stop(35, "#334155"), stop(70, "#475569"))
                    )
                )
            }
            B2BMode.DELIVERY -> {
                tvModeDetails.text = "Режим Курьер: Служебные входы ресторанов + таймер готовности"
                tvTargetHeader.text = "ПОГРУЗКА: Ресторан «Гриль-Хаус» (Служебный вход со двора)"
                tvTargetHeader.setTextColor(Color.parseColor("#00FF88"))
                tvTargetSub.text = "Заказ #408 готов через 01:45 • Код калитки: #4820 • 210 м"
                routeLayer?.setProperties(lineColor("#00FF88"))
                buildingLayer?.setProperties(
                    fillExtrusionColor(
                        interpolate(linear(), coalesce(get("render_height"), literal(12)),
                            stop(0, "#132A24"), stop(35, "#1B3B32"), stop(70, "#254E42"))
                    )
                )
            }
            B2BMode.RESTAURANT -> {
                tvModeDetails.text = "B2B-Диспетчер: Тепловая карта курьеров и контроль времени подачи"
                tvTargetHeader.text = "ДИСПЕТЧЕР: 4 курьера в радиусе 3 мин • Среднее время 11 мин"
                tvTargetHeader.setTextColor(Color.parseColor("#FF5252"))
                tvTargetSub.text = "Зона экспресс-доставки активна • Нагрузка на кухню: Оптимально"
                routeLayer?.setProperties(lineColor("#FF5252"))
                buildingLayer?.setProperties(
                    fillExtrusionColor(
                        interpolate(linear(), coalesce(get("render_height"), literal(12)),
                            stop(0, "#2B1822"), stop(35, "#3D202F"), stop(70, "#522A3E"))
                    )
                )
            }
        }
    }

    private fun startSimulation() {
        simHandler.post(object : Runnable {
            override fun run() {
                if (!isSimulating) return
                simStep++
                val rad = Math.toRadians(currentBearing)
                val speedStep = 0.000045
                currentPos = LatLng(
                    currentPos.latitude + cos(rad) * speedStep,
                    currentPos.longitude + sin(rad) * speedStep
                )
                if (simStep % 45 == 0) {
                    currentBearing = (currentBearing + 25.0) % 360.0
                }
                updateCarOnMap(currentPos, currentBearing)
                simHandler.postDelayed(this, 60)
            }
        })
    }

    private fun updateCarOnMap(pos: LatLng, bearing: Double) {
        mapLibreMap?.style?.getSourceAs<GeoJsonSource>("car-source")
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(pos.longitude, pos.latitude)))
        moveCamera3D(pos, bearing, 17.8, currentPitch)
    }

    private fun moveCamera3D(target: LatLng, bearing: Double, zoom: Double, pitch: Double) {
        val cam = CameraPosition.Builder()
            .target(target)
            .zoom(zoom)
            .bearing(bearing)
            .tilt(pitch)
            .build()
        mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 250)
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRealGps()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRealGps() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, this)
        } catch (e: Exception) {
            Toast.makeText(this, "Используется демо-режим GNSS", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLocationChanged(location: Location) {
        if (isSimulating) return
        currentPos = LatLng(location.latitude, location.longitude)
        if (location.hasBearing()) {
            currentBearing = location.bearing.toDouble()
        }
        updateCarOnMap(currentPos, currentBearing)
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onDestroy() {
        super.onDestroy()
        simHandler.removeCallbacksAndMessages(null)
        mapView.onDestroy()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
}
