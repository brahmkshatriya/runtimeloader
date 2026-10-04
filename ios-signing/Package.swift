// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RuntimeLoaderIOSSigning",
    platforms: [
        .iOS(.v15),
    ],
    products: [
        .library(name: "RuntimeLoaderIOSSigning", targets: ["RuntimeLoaderIOSSigning"]),
    ],
    dependencies: [
        .package(url: "https://github.com/rorkai/rork-sign.git", exact: "0.6.5"),
    ],
    targets: [
        .target(
            name: "RuntimeLoaderIOSSigning",
            dependencies: [
                .product(name: "RorkSign", package: "rork-sign"),
            ]
        ),
    ]
)
