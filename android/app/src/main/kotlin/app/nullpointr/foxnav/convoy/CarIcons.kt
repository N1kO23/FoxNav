package app.nullpointr.foxnav.convoy

import androidx.compose.ui.graphics.vector.ImageVector
import app.nullpointr.foxnav.protocol.convoy.v0.Car
import app.nullpointr.foxnav.ui.icons.FoxIcons

/** The car's shape seen from above; an arrow when nobody said. */
val Car.BodyStyle.icon: ImageVector
    get() = when (this) {
        Car.BodyStyle.BODY_STYLE_COUPE -> FoxIcons.CarCoupe
        Car.BodyStyle.BODY_STYLE_HATCHBACK -> FoxIcons.CarHatchback
        Car.BodyStyle.BODY_STYLE_SEDAN -> FoxIcons.CarSedan
        Car.BodyStyle.BODY_STYLE_WAGON -> FoxIcons.CarWagon
        Car.BodyStyle.BODY_STYLE_CONVERTIBLE -> FoxIcons.CarConvertible
        Car.BodyStyle.BODY_STYLE_KEI -> FoxIcons.CarKei
        Car.BodyStyle.BODY_STYLE_SUV -> FoxIcons.CarSuv
        Car.BodyStyle.BODY_STYLE_VAN -> FoxIcons.CarVan
        Car.BodyStyle.BODY_STYLE_PICKUP -> FoxIcons.CarPickup
        Car.BodyStyle.BODY_STYLE_UNSPECIFIED -> FoxIcons.CarMarker
    }
